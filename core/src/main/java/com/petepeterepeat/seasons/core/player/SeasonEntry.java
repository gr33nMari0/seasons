// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.player;

import com.petepeterepeat.seasons.core.config.ConfigSnapshot;
import com.petepeterepeat.seasons.core.guard.Immunity;
import com.petepeterepeat.seasons.core.log.SeasonsLog;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Brings one online player into a launched season: to its spawn in survival, with the season title,
 * arrival immunity and their statistics baseline, and their registered pets after them. At launch
 * for everyone online, and at the first login after it for everyone else.
 */
public final class SeasonEntry {
  private static final Logger LOG = SeasonsLog.get();

  private final Platform platform;
  private final Supplier<ConfigSnapshot> config;
  private final PlayerStore store;
  private final Immunity immunity;
  private final Clock clock;
  private final Consumer<PlayerId> arrived;
  private final Executor serverThread;

  /**
   * @param arrived told once a player has reached a season's spawn, such as for their pets to
   *     follow
   */
  public SeasonEntry(
      Platform platform,
      Supplier<ConfigSnapshot> config,
      PlayerStore store,
      Immunity immunity,
      Clock clock,
      Consumer<PlayerId> arrived) {
    this.platform = platform;
    this.config = config;
    this.store = store;
    this.immunity = immunity;
    this.clock = clock;
    this.arrived = arrived;
    this.serverThread = platform.scheduler()::runGlobal;
  }

  /** Where a season starts, standing in the middle of the block, or empty if no spawn is set. */
  public Optional<Position> spawn(int season) {
    return config.get().season(season).flatMap(file -> file.spawn()).map(SeasonEntry::centre);
  }

  /**
   * A first-time joiner after launch: to the season's spawn, unless they joined at a world spawn
   * inside the Hall, where they stay and find their way by its links (questions 247).
   */
  public CompletableFuture<Boolean> welcome(PlayerId player, int season) {
    if (platform.players().find(player).isPresent()
        && config.get().inHall(platform.players().position(player))) {
      takeBaseline(player, season);
      return CompletableFuture.completedFuture(false);
    }
    return enter(player, season);
  }

  /** Sends a player to a season's spawn; completes with whether they reached it. */
  public CompletableFuture<Boolean> enter(PlayerId player, int season) {
    Optional<Position> spawn = spawn(season);
    if (spawn.isEmpty()) {
      LOG.warn("Season {} has no spawn, so {} stays where they are", season, player);
      takeBaseline(player, season);
      return CompletableFuture.completedFuture(false);
    }
    return platform
        .players()
        .teleportSafely(player, spawn.get())
        .thenApplyAsync(
            moved -> {
              Optional<PlayerRef> online = platform.players().find(player);
              if (!moved || online.isEmpty()) {
                LOG.warn(
                    "{} couldn't be moved to the Season {} spawn; they will be at next login",
                    player,
                    season);
                return false;
              }
              Duration immune = config.get().config().hof().arrivalImmunity();
              immunity.grant(player, immune);
              platform.players().setSurvival(player);
              Messages messages = config.get().messages();
              String number = Integer.toString(season);
              platform
                  .players()
                  .celebrate(
                      player,
                      messages.get("launch.title", "season", number),
                      messages.get("launch.subtitle", "season", number));
              takeBaseline(player, season);
              arrived.accept(player);
              return true;
            },
            serverThread);
  }

  /** Records that the player starts the season from their statistics as they are now. */
  public void takeBaseline(PlayerId player, int season) {
    Optional<PlayerRef> online = platform.players().find(player);
    if (online.isEmpty()) {
      return;
    }
    Map<String, Long> baseline = new java.util.HashMap<>(platform.statistics().general(player));
    baseline.putAll(platform.statistics().counts(player));
    PlayerRecord record =
        store
            .getOrCreate(player, online.get().name())
            .withEntered(season, baseline)
            .withPlay(season, clock.instant(), Duration.ZERO);
    store.put(record);
  }

  static Position centre(Position block) {
    return new Position(
        block.dimension(),
        block.blockX() + 0.5,
        block.y(),
        block.blockZ() + 0.5,
        block.yaw(),
        block.pitch());
  }
}
