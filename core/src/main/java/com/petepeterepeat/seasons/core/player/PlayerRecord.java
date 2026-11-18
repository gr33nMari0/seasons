// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.player;

import com.petepeterepeat.seasons.core.port.GameMode;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * {@code data/players/<uuid>.json}: what Seasons knows about one player. Records follow the UUID;
 * the name is the last one seen. Changed only on the server thread.
 *
 * @param seasons the seasons they played, with play time and first and last seen
 * @param archivedThrough the latest season they have been archived into, or 0
 * @param seasonEntered the latest season they have been brought into: sent to its spawn with their
 *     stat baseline taken, at launch or at their first login after it
 * @param baselines vanilla General-tab statistics at the start of each season, by season
 * @param furthest furthest block placed, by season
 * @param pendingDisplays seasons whose displays still need rendering
 * @param pendingDestination a season archived whose cell they haven't been sent to yet
 * @param hallAdventure Seasons switched them from survival to adventure in the Hall, so leaving it
 *     puts survival back; kept here so a restart doesn't strand them in adventure
 * @param hallReturn where a visitor to the Hall goes back to, and in which game mode; kept here so
 *     it survives logging out and restarts
 */
public record PlayerRecord(
    PlayerId id,
    String lastName,
    Map<Integer, SeasonPlay> seasons,
    int archivedThrough,
    int seasonEntered,
    Map<Integer, Map<String, Long>> baselines,
    Map<Integer, FurthestBlock> furthest,
    Set<Integer> pendingDisplays,
    OptionalInt pendingDestination,
    boolean hallAdventure,
    Optional<HallReturn> hallReturn) {
  public PlayerRecord {
    seasons = Map.copyOf(seasons);
    baselines = Map.copyOf(baselines);
    furthest = Map.copyOf(furthest);
    pendingDisplays = Set.copyOf(pendingDisplays);
  }

  /**
   * Time in one season.
   *
   * @param playTime online time while the season was active
   */
  public record SeasonPlay(Instant firstSeen, Instant lastSeen, Duration playTime) {}

  /**
   * Where a Hall visitor returns to.
   *
   * @param mode the game mode they had when they set off
   */
  public record HallReturn(Position at, GameMode mode) {}

  public static PlayerRecord create(PlayerId id, String name) {
    return new PlayerRecord(
        id,
        name,
        Map.of(),
        0,
        0,
        Map.of(),
        Map.of(),
        Set.of(),
        OptionalInt.empty(),
        false,
        Optional.empty());
  }

  public boolean played(int season) {
    return seasons.containsKey(season);
  }

  public PlayerRecord withName(String name) {
    return new PlayerRecord(
        id,
        name,
        seasons,
        archivedThrough,
        seasonEntered,
        baselines,
        furthest,
        pendingDisplays,
        pendingDestination,
        hallAdventure,
        hallReturn);
  }

  /** Marks the season played and adds online time to it. */
  public PlayerRecord withPlay(int season, Instant now, Duration more) {
    Map<Integer, SeasonPlay> next = new HashMap<>(seasons);
    SeasonPlay play = seasons.get(season);
    next.put(
        season,
        play == null
            ? new SeasonPlay(now, now, more)
            : new SeasonPlay(play.firstSeen(), now, play.playTime().plus(more)));
    return new PlayerRecord(
        id,
        lastName,
        next,
        archivedThrough,
        seasonEntered,
        baselines,
        furthest,
        pendingDisplays,
        pendingDestination,
        hallAdventure,
        hallReturn);
  }

  /** Archived into a season; a saved Hall return is dropped, since it belongs to that season. */
  public PlayerRecord withArchived(int season) {
    Set<Integer> displays = new HashSet<>(pendingDisplays);
    displays.add(season);
    return new PlayerRecord(
        id,
        lastName,
        seasons,
        Math.max(archivedThrough, season),
        seasonEntered,
        baselines,
        furthest,
        displays,
        OptionalInt.of(season),
        hallAdventure,
        Optional.empty());
  }

  /**
   * After {@code /season unarchive}: no longer archived into {@code season}. Only unarchiving the
   * latest archived season lowers {@link #archivedThrough}; unarchiving an older one leaves it, so
   * later seasons, still archived, are never archived over again.
   */
  public PlayerRecord withUnarchived(int season) {
    Set<Integer> displays = new HashSet<>(pendingDisplays);
    displays.remove(season);
    OptionalInt destination =
        pendingDestination.isPresent() && pendingDestination.getAsInt() == season
            ? OptionalInt.empty()
            : pendingDestination;
    return new PlayerRecord(
        id,
        lastName,
        seasons,
        archivedThrough == season ? season - 1 : archivedThrough,
        seasonEntered,
        baselines,
        furthest,
        displays,
        destination,
        hallAdventure,
        hallReturn);
  }

  public PlayerRecord withDisplaysRendered(int season) {
    Set<Integer> displays = new HashSet<>(pendingDisplays);
    displays.remove(season);
    return new PlayerRecord(
        id,
        lastName,
        seasons,
        archivedThrough,
        seasonEntered,
        baselines,
        furthest,
        displays,
        pendingDestination,
        hallAdventure,
        hallReturn);
  }

  public PlayerRecord withDestinationReached() {
    return new PlayerRecord(
        id,
        lastName,
        seasons,
        archivedThrough,
        seasonEntered,
        baselines,
        furthest,
        pendingDisplays,
        OptionalInt.empty(),
        hallAdventure,
        hallReturn);
  }

  /** Brought into a season, with the statistics it starts from; a saved Hall return is dropped. */
  public PlayerRecord withEntered(int season, Map<String, Long> baseline) {
    Map<Integer, Map<String, Long>> next = new HashMap<>(baselines);
    next.put(season, Map.copyOf(baseline));
    return new PlayerRecord(
        id,
        lastName,
        seasons,
        archivedThrough,
        Math.max(seasonEntered, season),
        next,
        furthest,
        pendingDisplays,
        pendingDestination,
        hallAdventure,
        Optional.empty());
  }

  public FurthestBlock furthest(int season) {
    return furthest.getOrDefault(season, FurthestBlock.NONE);
  }

  public PlayerRecord withFurthest(int season, FurthestBlock best) {
    Map<Integer, FurthestBlock> next = new HashMap<>(furthest);
    next.put(season, best);
    return new PlayerRecord(
        id,
        lastName,
        seasons,
        archivedThrough,
        seasonEntered,
        baselines,
        next,
        pendingDisplays,
        pendingDestination,
        hallAdventure,
        hallReturn);
  }

  public PlayerRecord withHallAdventure(boolean switched) {
    return new PlayerRecord(
        id,
        lastName,
        seasons,
        archivedThrough,
        seasonEntered,
        baselines,
        furthest,
        pendingDisplays,
        pendingDestination,
        switched,
        hallReturn);
  }

  public PlayerRecord withHallReturn(Optional<HallReturn> next) {
    return new PlayerRecord(
        id,
        lastName,
        seasons,
        archivedThrough,
        seasonEntered,
        baselines,
        furthest,
        pendingDisplays,
        pendingDestination,
        hallAdventure,
        next);
  }

  public Optional<Map<String, Long>> baseline(int season) {
    return Optional.ofNullable(baselines.get(season));
  }
}
