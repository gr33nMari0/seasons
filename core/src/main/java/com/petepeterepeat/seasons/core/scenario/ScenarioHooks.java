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
import com.petepeterepeat.seasons.core.vault.Vault;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
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
  private final Map<PlayerId, CrashPoint> saveAt = new ConcurrentHashMap<>();
  private volatile Runnable save = () -> {};
  private final List<Runnable> beforeHalt = new CopyOnWriteArrayList<>();
  private final Runnable halt;
  private final ScenarioClock clock;
  private final ScenarioScheduler scheduler;
  private volatile Consumer<Runnable> restart = Runnable::run;
  private volatile @org.jspecify.annotations.Nullable Wiring wiring;

  /** What the hooks read from, set once Seasons has started. */
  public record Wiring(
      ArchiveFiles archives,
      VaultFiles vaults,
      Vault vault,
      PlayerStore store,
      JournalRecovery recovery,
      Menus menus,
      SerialWriter writer,
      Supplier<ConfigSnapshot> config,
      java.util.concurrent.Executor serverThread,
      java.util.function.BiFunction<PlayerId, Integer, CompletableFuture<List<String>>> cards) {}

  /**
   * @param halt stops the server dead, as a crash would
   * @param clock the clock Seasons runs on, which {@link #advance} moves
   * @param scheduler the scheduler Seasons runs on, which {@link #advance} catches up
   */
  public ScenarioHooks(Runnable halt, ScenarioClock clock, ScenarioScheduler scheduler) {
    this.halt = halt;
    this.clock = clock;
    this.scheduler = scheduler;
  }

  /**
   * How {@link #reset} starts Seasons again as a fresh install: stop it, run the task it is given,
   * delete its config and data folders, and start a new one. Set by the platform's plugin.
   */
  public void onReset(Consumer<Runnable> restart) {
    this.restart = restart;
  }

  /**
   * Returns Seasons to a fresh install: no crash point armed, the clock back to real time, and
   * Seasons stopped, its files deleted and started again. {@code whileStopped} runs once Seasons
   * has stopped and before its files are deleted, so whatever Seasons might still write while the
   * world is put back is deleted too. Runs on the thread that owns global state, with nobody
   * online.
   */
  public void reset(Runnable whileStopped) {
    LOG.warn("Scenario reset: Seasons starts again as a fresh install");
    armed.clear();
    saveAt.clear();
    wiring = null;
    restart.accept(() -> {
      scheduler.forgetDelayed();
      clock.reset();
      whileStopped.run();
    });
  }

  /**
   * Moves Seasons' clock forward and runs at once whatever came due in the time skipped. Runs on
   * the thread that owns global state; returns the new time.
   */
  public Instant advance(Duration by) {
    Instant now = clock.advance(by);
    scheduler.catchUp();
    return now;
  }

  /** Seasons' time, which runs ahead of real time once {@link #advance} has moved it. */
  public Instant now() {
    return clock.instant();
  }

  /** Seasons' delayed and repeating tasks still to run, for the leak check after a reset. */
  public int scheduledTasks() {
    return scheduler.active();
  }

  public void wire(Wiring wiring) {
    this.wiring = wiring;
  }

  /** Stops the server dead the next time archiving {@code player} reaches {@code point}. */
  public void arm(CrashPoint point, PlayerId player) {
    armed.put(player, point);
  }

  /**
   * Saves the worlds to disk the next time {@code player} reaches {@code point}, without stopping,
   * so a later crash point finds the world as it was there rather than as it was at the last save.
   */
  public void armSave(CrashPoint point, PlayerId player) {
    saveAt.put(player, point);
  }

  /** How {@link #armSave} saves the worlds; set by the test plugin. */
  public void onSave(Runnable save) {
    this.save = save;
  }

  /** Runs just before a halt, such as the test plugin noting that the stop was asked for. */
  public void beforeHalt(Runnable task) {
    beforeHalt.add(task);
  }

  @Override
  public void reached(CrashPoint point, PlayerId player) {
    if (saveAt.remove(player, point)) {
      LOG.warn("Scenario save point {} reached for {}: saving the worlds", point, player);
      save.run();
    }
    if (armed.get(player) != point) {
      return;
    }
    LOG.warn("Scenario crash point {} reached for {}: halting the server", point, player);
    beforeHalt.forEach(Runnable::run);
    halt.run();
  }

  /**
   * Two lines: what the archive holds ({@code stacks= items= ender= vault= completed= visible=
   * pages=}, or {@code vault= none}), and the record ({@code archivedThrough= pending= returned=},
   * the last what waits on their Returned page).
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
              "Record of %s: archivedThrough=%d pending=%d returned=%d"
                  .formatted(name, through, pending, returned(w, player)));
        });
  }

  /** How many items wait on a player's Returned page. */
  private static int returned(Wiring w, PlayerId player) {
    try {
      return w.vaults().read(player).returned().stream()
          .flatMap(Optional::stream)
          .mapToInt(ScenarioHooks::count)
          .sum();
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  /** Hands an item back to a player on their vault's Returned page. */
  public CompletableFuture<Void> returnToVault(PlayerId player, NeutralItem item) {
    return wired().vault().returnItem(player, item, "returned by a scenario");
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
