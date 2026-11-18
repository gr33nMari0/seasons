// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.scenario;

import com.petepeterepeat.seasons.core.archive.ArchiveFiles;
import com.petepeterepeat.seasons.core.archive.ArchivePages;
import com.petepeterepeat.seasons.core.archive.ArchiveRecord;
import com.petepeterepeat.seasons.core.archive.VaultFiles;
import com.petepeterepeat.seasons.core.config.ConfigSnapshot;
import com.petepeterepeat.seasons.core.player.PlayerRecord;
import com.petepeterepeat.seasons.core.player.PlayerStore;
import com.petepeterepeat.seasons.core.port.Menus;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.store.JournalRecovery;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import com.petepeterepeat.seasons.core.store.nbt.Nbt;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the acceptance scenarios need that no Seasons command offers: stopping the server dead at
 * one step of archiving, reading what an archive holds, and opening an archive view before M1 part
 * 3 adds archive anchors. Seasons creates this only on a server started with {@code
 * -Dseasons.scenario=true}; on every other server it doesn't exist, and nothing can create it at
 * run time (docs/adr/0003-scenario-hooks.md).
 */
public final class ScenarioHooks implements CrashPoints {
  /** The system property that turns the hooks on, read once at startup. */
  public static final String PROPERTY = "seasons.scenario";

  private static final Logger LOG = LoggerFactory.getLogger(ScenarioHooks.class);

  private final Map<PlayerId, CrashPoint> armed = new ConcurrentHashMap<>();
  private final List<Runnable> beforeHalt = new CopyOnWriteArrayList<>();
  private final Runnable halt;
  private volatile @org.jspecify.annotations.Nullable Wiring wiring;

  /** What the hooks read from, set once Seasons has started. */
  public record Wiring(
      ArchiveFiles archives,
      VaultFiles vaults,
      PlayerStore store,
      JournalRecovery recovery,
      Menus menus,
      SerialWriter writer,
      Supplier<ConfigSnapshot> config,
      java.util.concurrent.Executor serverThread,
      java.util.function.BiFunction<PlayerId, Integer, CompletableFuture<List<String>>> cards) {}

  /**
   * @param halt stops the server dead, as a crash would
   */
  public ScenarioHooks(Runnable halt) {
    this.halt = halt;
  }

  public void wire(Wiring wiring) {
    this.wiring = wiring;
  }

  /** Stops the server dead the next time archiving {@code player} reaches {@code point}. */
  public void arm(CrashPoint point, PlayerId player) {
    armed.put(player, point);
  }

  /** Runs just before a halt, such as the test plugin noting that the stop was asked for. */
  public void beforeHalt(Runnable task) {
    beforeHalt.add(task);
  }

  @Override
  public void reached(CrashPoint point, PlayerId player) {
    if (armed.get(player) != point) {
      return;
    }
    LOG.warn("Scenario crash point {} reached for {}: halting the server", point, player);
    beforeHalt.forEach(Runnable::run);
    halt.run();
  }

  /**
   * Two lines: what the archive holds ({@code stacks= items= ender= vault= completed= visible=
   * pages=}, or {@code vault= none}), and the record ({@code archivedThrough= pending=}).
   */
  public CompletableFuture<List<String>> summary(PlayerId player, String name, int season) {
    Wiring w = wired();
    return w.writer()
        .submit(() -> {
          Optional<ArchiveRecord> archive = w.archives().read(player, season);
          int vault = w.vaults().page(player, season).map(ScenarioHooks::count).orElse(0);
          String first =
              archive
                  .map(a ->
                      "Archive of %s for Season %d: stacks=%d items=%d ender=%d vault=%d completed=%d visible=%d pages=%d"
                          .formatted(
                              name,
                              season,
                              a.carried().size()
                                  + (a.enderChestPolicy().name().equals("ARCHIVE")
                                      ? a.enderChest().size()
                                      : 0),
                              a.carried().stream().mapToInt(ScenarioHooks::count).sum()
                                  + (a.enderChestPolicy().name().equals("ARCHIVE")
                                      ? a.enderChest().stream().mapToInt(ScenarioHooks::count).sum()
                                      : 0),
                              a.enderChest().stream().mapToInt(ScenarioHooks::count).sum(),
                              vault,
                              a.advancements().completed(),
                              a.advancements().visible(),
                              ArchivePages.pages(a).size()))
                  .orElse(
                      "Archive of %s for Season %d: vault=%d none".formatted(name, season, vault));
          return first;
        })
        .thenApply(first -> {
          int through = w.store().find(player).map(PlayerRecord::archivedThrough).orElse(0);
          int pending = w.recovery().open(player).size();
          return List.of(
              first,
              "Record of %s: archivedThrough=%d pending=%d".formatted(name, through, pending));
        });
  }

  /** A player's season card as plain lines; empty if they have no archive for the season. */
  public CompletableFuture<List<String>> card(PlayerId player, int season) {
    return wired().cards().apply(player, season);
  }

  /** Opens one archive's view for a viewer; completes with false if there is no such archive. */
  public CompletableFuture<Boolean> openArchive(PlayerId viewer, PlayerId owner, int season) {
    Wiring w = wired();
    return w.writer()
        .submit(() -> w.archives().read(owner, season))
        .thenApplyAsync(
            archive -> {
              archive.ifPresent(a ->
                  w.menus().openPaged(viewer, ArchivePages.view(a, w.config().get().messages())));
              return archive.isPresent();
            },
            w.serverThread());
  }

  private Wiring wired() {
    Wiring w = wiring;
    if (w == null) {
      throw new IllegalStateException("Seasons hasn't started");
    }
    return w;
  }

  private static int count(List<Optional<NeutralItem>> slots) {
    return slots.stream().flatMap(Optional::stream).mapToInt(ScenarioHooks::count).sum();
  }

  private static int count(NeutralItem item) {
    try {
      return Nbt.read(item.nbt()).get("count") instanceof Number n ? n.intValue() : 1;
    } catch (IOException e) {
      return 1;
    }
  }
}
