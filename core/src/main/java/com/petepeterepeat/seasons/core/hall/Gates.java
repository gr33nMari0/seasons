// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.audit.AuditLog;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.config.SeasonsConfig;
import com.petepeterepeat.seasons.core.port.BlockInfo;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.ScheduledTask;
import com.petepeterepeat.seasons.core.scenario.CrashPoint;
import com.petepeterepeat.seasons.core.scenario.CrashPoints;
import com.petepeterepeat.seasons.core.store.Journal;
import com.petepeterepeat.seasons.core.store.JournalKind;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The gates of cells in galleries with access mode {@code gate} (questions 169): a cell's gate
 * plane is air while its owner may decorate it, and {@code cells.gateBlock} otherwise. Once a
 * second each gate whose chunks are loaded is compared with what it should be, which covers the
 * window opening and closing, an owner's time running out, launch, reloads and startup, and puts
 * back a gate changed behind Seasons' back. Every change is journalled ({@code GATE_SET}) before
 * the blocks change; recovery needs nothing more than the next pass. Server thread only.
 */
public final class Gates {
  private static final Logger LOG = LoggerFactory.getLogger(Gates.class);
  private static final Duration PASS = Duration.ofSeconds(1);
  private static final String AIR = "minecraft:air";

  private final Platform platform;
  private final ConfigHolder config;
  private final HallService halls;
  private final Journal journal;
  private final AuditLog audit;
  private final CrashPoints crash;
  private final Executor serverThread;

  /** Whether each gate, by cell key, was last set open, as far as Seasons knows. */
  private final Map<String, Boolean> applied = new HashMap<>();

  /** Gates whose change is being journalled now. */
  private final Set<String> changing = new HashSet<>();

  private @Nullable ScheduledTask passes;

  public Gates(
      Platform platform,
      ConfigHolder config,
      HallService halls,
      Journal journal,
      AuditLog audit,
      CrashPoints crash) {
    this.platform = platform;
    this.config = config;
    this.halls = halls;
    this.journal = journal;
    this.audit = audit;
    this.crash = crash;
    this.serverThread = platform.scheduler()::runGlobal;
  }

  public void start() {
    passes = platform.scheduler().runGlobalRepeating(PASS, this::pass);
  }

  public void stop() {
    if (passes != null) {
      passes.cancel();
      passes = null;
    }
  }

  /** Sets every loaded gate that isn't what it should be. */
  void pass() {
    SeasonsConfig settings = config.get().config();
    String shut = blockId(settings.cells().gateBlock());
    Set<String> seen = new HashSet<>();
    for (Gallery gallery : halls.galleries()) {
      if (gallery.access(settings) != SeasonsConfig.CellAccess.GATE) {
        continue;
      }
      for (Cell cell : gallery.cells()) {
        Optional<Bounds> plane = cell.gate();
        if (plane.isEmpty()) {
          continue;
        }
        HallService.CellRef ref = new HallService.CellRef(gallery, cell);
        String key = ref.key();
        seen.add(key);
        List<Position> blocks = plane.get().blocks();
        if (changing.contains(key) || !blocks.stream().allMatch(platform.world()::isLoaded)) {
          continue;
        }
        boolean open = open(ref);
        String wanted = open ? AIR : shut;
        boolean matches =
            blocks.stream()
                .map(platform.world()::block)
                .map(BlockInfo::type)
                .allMatch(wanted::equals);
        Boolean was = applied.get(key);
        if (matches) {
          applied.put(key, open);
        } else {
          set(ref, blocks, open, wanted, was != null && was == open);
        }
      }
    }
    applied.keySet().retainAll(seen);
  }

  /** Whether a cell's gate should be open: while its owner may decorate it. */
  private boolean open(HallService.CellRef ref) {
    return ref.cell()
        .owner(ref.gallery().season())
        .map(owner -> halls.canDecorate(owner, ref))
        .orElse(false);
  }

  private void set(
      HallService.CellRef ref, List<Position> blocks, boolean open, String type, boolean repair) {
    String key = ref.key();
    changing.add(key);
    Optional<PlayerId> owner = ref.cell().owner(ref.gallery().season());
    String state = open ? "open" : "shut";
    var _ =
        journal
            .append(
                JournalKind.GATE_SET, owner.orElse(null), ref.gallery().season(), key + "/" + state)
            .whenCompleteAsync(
                (entry, failure) -> {
                  changing.remove(key);
                  if (failure != null) {
                    LOG.error("Can't journal the gate of {}, so it stays as it is", key, failure);
                    return;
                  }
                  owner.ifPresent(o -> crash.reached(CrashPoint.GATE_SET, o));
                  platform.world().setBlocks(blocks, type);
                  applied.put(key, open);
                  if (repair) {
                    audit.record(AuditLog.SEASONS, "reconcile", key, "repaired", "gate " + state);
                  }
                  if (!open) {
                    putOut(ref);
                  }
                },
                serverThread);
  }

  /** Moves everyone but bypass holders out of a cell whose gate just shut. */
  private void putOut(HallService.CellRef ref) {
    Optional<Position> entry = halls.galleryEntry(ref.gallery());
    if (entry.isEmpty()) {
      return;
    }
    for (PlayerRef online : platform.players().online()) {
      PlayerId player = online.id();
      if (ref.cell().contains(platform.players().position(player))
          && !platform.permissions().has(player, HallService.BYPASS)) {
        var _ =
            platform
                .players()
                .teleportSafely(player, entry.get())
                .thenAcceptAsync(
                    moved -> {
                      if (Boolean.TRUE.equals(moved)) {
                        platform
                            .players()
                            .sendMessage(player, config.get().messages().get("hof.cell.gate-shut"));
                      }
                    },
                    serverThread);
      }
    }
  }

  /** {@code iron_bars} as {@code minecraft:iron_bars}. */
  static String blockId(String name) {
    return name.indexOf(':') < 0 ? "minecraft:" + name : name;
  }
}
