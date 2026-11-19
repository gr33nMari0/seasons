// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.announce.Announcer;
import com.petepeterepeat.seasons.core.audit.AuditLog;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.config.ConfigSnapshot;
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
 * the blocks change; recovery needs nothing more than the next pass.
 *
 * <p>A gate is never set over a block with contents of its own, such as a chest, so no item is
 * lost: it stays as it is, and admins are told, until the block is gone. A gate that doesn't stay
 * set ({@value #ATTEMPTS} tries at the same state without a pass finding it right, as with a block
 * knocked out each time) is left alone, and admins are told, until what it should be changes or the
 * config is reloaded, so it can't fill the journal. Server thread only.
 */
public final class Gates {
  private static final Logger LOG = LoggerFactory.getLogger(Gates.class);
  private static final Duration PASS = Duration.ofSeconds(1);
  private static final String AIR = "minecraft:air";

  /** Tries at setting a gate, with no pass finding it right between, before it is left alone. */
  static final int ATTEMPTS = 3;

  private final Platform platform;
  private final ConfigHolder config;
  private final HallService halls;
  private final Journal journal;
  private final AuditLog audit;
  private final CrashPoints crash;
  private final Announcer announcer;
  private final Executor serverThread;

  /** Whether each gate, by cell key, was last set open, as far as Seasons knows. */
  private final Map<String, Boolean> applied = new HashMap<>();

  /** Gates whose change is being journalled now. */
  private final Set<String> changing = new HashSet<>();

  /** Tries at each gate since a pass last found it right. */
  private final Map<String, Tries> tries = new HashMap<>();

  /** Gates left alone because they don't stay set, with what held them. */
  private final Map<String, Held> held = new HashMap<>();

  /** Gates left alone because a block in their plane has contents. */
  private final Set<String> blocked = new HashSet<>();

  /**
   * Tries at setting a gate {@code open} (or shut). A change of what it should be starts again,
   * since the window may open and close faster than a pass.
   */
  private record Tries(boolean open, int count) {}

  /** A gate left alone while it should be {@code open} under this config. */
  private record Held(boolean open, ConfigSnapshot config) {}

  private @Nullable ScheduledTask passes;

  public Gates(
      Platform platform,
      ConfigHolder config,
      HallService halls,
      Journal journal,
      AuditLog audit,
      CrashPoints crash,
      Announcer announcer) {
    this.announcer = announcer;
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

  /**
   * Sets every loaded gate that isn't what it should be. A held gate is let go by a new config
   * snapshot, which every reload makes, so the snapshot is compared by reference.
   */
  @SuppressWarnings("ReferenceEquality")
  void pass() {
    ConfigSnapshot snapshot = config.get();
    SeasonsConfig settings = snapshot.config();
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
          tries.remove(key);
          held.remove(key);
          blocked.remove(key);
          continue;
        }
        Held stuck = held.get(key);
        if (stuck != null && stuck.open() == open && stuck.config() == snapshot) {
          continue;
        }
        held.remove(key);
        Optional<Position> contents =
            blocks.stream().filter(platform.world()::holdsContents).findFirst();
        if (contents.isPresent()) {
          if (blocked.add(key)) {
            leftAlone(ref, "contents", contents.get());
          }
          continue;
        }
        blocked.remove(key);
        Tries before = tries.get(key);
        int tried = before != null && before.open() == open ? before.count() + 1 : 1;
        tries.put(key, new Tries(open, tried));
        if (tried > ATTEMPTS) {
          tries.remove(key);
          held.put(key, new Held(open, snapshot));
          leftAlone(ref, "unsteady", blocks.getFirst());
          continue;
        }
        set(ref, blocks, open, wanted, was != null && was == open);
      }
    }
    applied.keySet().retainAll(seen);
    tries.keySet().retainAll(seen);
    held.keySet().retainAll(seen);
    blocked.retainAll(seen);
  }

  /**
   * Tells admins, the log and the audit log that a gate is left as it is: a block in its plane has
   * contents ({@code contents}), or it doesn't stay set ({@code unsteady}).
   */
  private void leftAlone(HallService.CellRef ref, String why, Position at) {
    String where = at.blockX() + ", " + at.blockY() + ", " + at.blockZ();
    audit.record(AuditLog.SEASONS, "gate", ref.key(), "left alone", why + " at " + where);
    announcer.admins(
        HallCommands.PERMISSION_ADMIN,
        "hof.gate." + why,
        "cell",
        ref.key(),
        "x",
        Integer.toString(at.blockX()),
        "y",
        Integer.toString(at.blockY()),
        "z",
        Integer.toString(at.blockZ()));
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
                  // Checked again: something with contents may have been put there meanwhile.
                  if (blocks.stream().anyMatch(platform.world()::holdsContents)) {
                    return;
                  }
                  try {
                    platform.world().setBlocks(blocks, type);
                  } catch (RuntimeException e) {
                    LOG.error("Can't set the gate of {}", key, e);
                    return;
                  }
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
