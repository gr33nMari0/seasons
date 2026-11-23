// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.season;

import com.petepeterepeat.seasons.core.announce.Announcer;
import com.petepeterepeat.seasons.core.config.ConfigSnapshot;
import com.petepeterepeat.seasons.core.log.DebugArea;
import com.petepeterepeat.seasons.core.log.SeasonsLog;
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

/**
 * Launch: the world spawn set to the new season's spawn, the dragon flag reset, a new deaths
 * objective, then everyone online brought to the spawn in survival with the season title. Zones
 * lock with M3 and pets travel with M3; cells lock with M1 part 3. Afterwards the journal is
 * compacted, keeping only operations still open.
 */
public final class LaunchSequence implements SeasonService.Launcher {
  private static final Logger LOG = SeasonsLog.get();

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
  private final Runnable dragonFlag;
  private final Executor serverThread;

  public LaunchSequence(
      Platform platform,
      Supplier<ConfigSnapshot> config,
      Announcer announcer,
      SeasonEntry entry,
      Supplier<Set<PlayerId>> busy,
      BiPredicate<PlayerId, Integer> unsettled,
      Optional<Journal> journal,
      Runnable dragonFlag) {
    this.platform = platform;
    this.config = config;
    this.announcer = announcer;
    this.entry = entry;
    this.busy = busy;
    this.unsettled = unsettled;
    this.journal = journal;
    this.dragonFlag = dragonFlag;
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
    if (config.get().season(nextSeason).flatMap(file -> file.zoneHoldingSpawn()).isPresent()) {
      return Optional.of("launch.spawn-in-zone");
    }
    if (config.get().inHall(spawn.get())) {
      return Optional.of("launch.spawn-in-hall");
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

  /**
   * The season's own deaths objective, made if it is missing, in the slot {@code deaths.display}
   * names; earlier seasons' objectives are kept, off the screen.
   */
  @Override
  public void showDeaths(SeasonState current) {
    String number = Integer.toString(current.season());
    platform
        .players()
        .deathsObjective(
            "seasons_deaths_s" + current.season(),
            config.get().messages().get("launch.deaths", "season", number),
            config.get().config().deaths().display());
  }

  @Override
  public CompletableFuture<Void> apply(SeasonState launched) {
    int season = launched.season();
    String number = Integer.toString(season);
    Position spawn =
        entry
            .spawn(season)
            .orElseThrow(() -> new IllegalStateException("Season " + season + " has no spawn"));
    if (config.get().config().launch().setWorldSpawn()) {
      platform.world().setSpawn(spawn);
    }
    dragonFlag.run();
    showDeaths(launched);
    announcer.adminsNote(
        DebugArea.COMMANDS,
        SeasonService.ADMIN_PERMISSION,
        "launch.loading",
        "x",
        Integer.toString(spawn.blockX()),
        "y",
        Integer.toString(spawn.blockY()),
        "z",
        Integer.toString(spawn.blockZ()));
    int sent = platform.players().online().size();
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
              LOG.info(
                  "Season {} launched: {} sent to the new spawn",
                  season,
                  sent == 1 ? "1 player" : sent + " players");
              announcer.broadcast("launch.done", "season", number);
              announcer.discord("launch.discord", "season", number);
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
                SeasonsLog.debug(
                    DebugArea.ARCHIVE,
                    "Compacted the journal after launch; {} entries still open",
                    kept);
              }
            });
  }
}
