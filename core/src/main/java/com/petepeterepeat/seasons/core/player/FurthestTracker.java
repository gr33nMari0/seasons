// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.player;

import com.petepeterepeat.seasons.core.config.ConfigSnapshot;
import com.petepeterepeat.seasons.core.hall.HallBounds;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.World;
import com.petepeterepeat.seasons.core.season.SeasonState;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Furthest block placed: on every block placed in the overworld while a season is played, one
 * distance comparison in memory. The record is saved with the player's other changes.
 */
public final class FurthestTracker {
  /** The season's furthest block from 0,0 and who placed it, for {@code /season status}. */
  public record SeasonBest(String name, FurthestBlock.Best best) {}

  private final Supplier<Optional<SeasonState>> state;
  private final Supplier<ConfigSnapshot> config;
  private final PlayerStore store;
  private final HallBounds hall;
  private final World world;

  public FurthestTracker(
      Supplier<Optional<SeasonState>> state,
      Supplier<ConfigSnapshot> config,
      PlayerStore store,
      HallBounds hall,
      World world) {
    this.state = state;
    this.config = config;
    this.store = store;
    this.hall = hall;
    this.world = world;
  }

  /** A player placed a block. */
  public void placed(PlayerId player, Position block) {
    Optional<SeasonState> current = state.get();
    if (current.isEmpty()
        || !LoginService.isPlaying(current.get().phase())
        || !FurthestBlock.OVERWORLD.equals(block.dimension())
        || hall.contains(block)) {
      return;
    }
    Optional<PlayerRecord> record = store.find(player);
    if (record.isEmpty()) {
      return;
    }
    int season = current.get().season();
    FurthestBlock before = record.get().furthest(season);
    FurthestBlock after = before.place(block, spawn(season));
    if (!after.equals(before)) {
      store.put(record.get().withFurthest(season, after));
    }
  }

  /** The season's furthest block from 0,0. */
  public Optional<SeasonBest> seasonBest(int season) {
    return store.all().stream()
        .flatMap(record ->
            record.furthest(season).fromOrigin().stream()
                .map(best -> new SeasonBest(record.lastName(), best)))
        .max(Comparator.comparingLong(best -> best.best().distance()));
  }

  /**
   * The season spawn, or the world spawn for a season with none set (such as the one init
   * recorded).
   */
  private Position spawn(int season) {
    return config
        .get()
        .season(season)
        .flatMap(file -> file.spawn())
        .orElseGet(() -> world.spawn(FurthestBlock.OVERWORLD));
  }
}
