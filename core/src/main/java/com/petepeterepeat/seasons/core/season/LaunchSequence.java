// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.season;

import com.petepeterepeat.seasons.core.announce.Announcer;
import com.petepeterepeat.seasons.core.config.ConfigSnapshot;
import com.petepeterepeat.seasons.core.player.SeasonEntry;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.store.Journal;
import com.petepeterepeat.seasons.core.store.JournalEntry;
import com.petepeterepeat.seasons.core.store.JournalRecovery;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launch: the world spawn set to the new season's spawn, the dragon flag reset, a new deaths
 * objective, then everyone online brought to the spawn in survival with the season title. Zones
 * lock with M3 and pets travel with M3; cells lock with M1 part 3. Afterwards the journal is
 * compacted, keeping only operations still open.
 */
public final class LaunchSequence implements SeasonService.Launcher {
  private static final Logger LOG = LoggerFactory.getLogger(LaunchSequence.class);

  private final Platform platform;
  private final Supplier<ConfigSnapshot> config;
  private final Announcer announcer;
  private final SeasonEntry entry;
  private final Supplier<Set<PlayerId>> busy;

  /**
   * Whether an online player isn't archived into the season that ended, or has an operation still
   * open in the journal.
   */
  private final BiPredicate<PlayerId, Integer> unsettled;

  private final Optional<Journal> journal;
  private final Executor serverThread;

  public LaunchSequence(
      Platform platform,
      Supplier<ConfigSnapshot> config,
      Announcer announcer,
      SeasonEntry entry,
      Supplier<Set<PlayerId>> busy,
      BiPredicate<PlayerId, Integer> unsettled,
      Optional<Journal> journal) {
    this.platform = platform;
    this.config = config;
    this.announcer = announcer;
    this.entry = entry;
    this.busy = busy;
    this.unsettled = unsettled;
    this.journal = journal;
    this.serverThread = platform.scheduler()::runGlobal;
  }

  @Override
  public Optional<String> refusal(int nextSeason) {
    Optional<Position> spawn = entry.spawn(nextSeason);
    if (spawn.isEmpty()) {
      return Optional.of("launch.no-spawn");
    }
    if (!platform.world().dimensionExists(spawn.get().dimension())) {
      return Optional.of("launch.bad-dimension");
    }
    if (!busy.get().isEmpty()) {
      return Optional.of("launch.busy");
    }
    // Everyone online goes into the new season with what they carry, so each must be archived
    // into the season that ended first: a failed archive or a crash still open would carry
    // archived items across.
    int ended = nextSeason - 1;
    if (platform.players().online().stream().anyMatch(p -> unsettled.test(p.id(), ended))) {
      return Optional.of("launch.not-archived");
    }
    return Optional.empty();
  }

  @Override
  public CompletableFuture<Void> apply(SeasonState launched) {
    int season = launched.season();
    String number = Integer.toString(season);
    Position spawn =
        entry
            .spawn(season)
            .orElseThrow(() -> new IllegalStateException("Season " + season + " has no spawn"));
    platform.world().setSpawn(spawn);
    platform.dragonFight().setPreviouslyKilled(false);
    platform
        .players()
        .deathsObjective(
            "seasons_deaths_s" + season,
            config.get().messages().get("launch.deaths", "season", number),
            config.get().config().deaths().display());
    announcer.admins(
        SeasonService.ADMIN_PERMISSION,
        "launch.loading",
        "x",
        Integer.toString(spawn.blockX()),
        "y",
        Integer.toString(spawn.blockY()),
        "z",
        Integer.toString(spawn.blockZ()));
    return platform
        .world()
        .loadChunk(spawn)
        .thenComposeAsync(
            loaded ->
                CompletableFuture.allOf(
                    platform.players().online().stream()
                        .map(PlayerRef::id)
                        .map(player -> entry.enter(player, season))
                        .toArray(CompletableFuture[]::new)),
            serverThread)
        .thenRunAsync(
            () -> {
              announcer.broadcast("launch.done", "season", number);
              compactJournal();
            },
            serverThread);
  }

  private void compactJournal() {
    if (journal.isEmpty()) {
      return;
    }
    Set<Long> open =
        JournalRecovery.openOperations(journal.get().entries()).stream()
            .flatMap(operation -> operation.steps().stream())
            .map(JournalEntry::sequence)
            .collect(Collectors.toSet());
    var _ =
        journal
            .get()
            .compact(entry -> open.contains(entry.sequence()))
            .whenComplete((kept, failure) -> {
              if (failure != null) {
                LOG.error("Can't compact the journal after launch", failure);
              } else {
                LOG.info("Compacted the journal after launch; {} entries still open", kept);
              }
            });
  }
}
