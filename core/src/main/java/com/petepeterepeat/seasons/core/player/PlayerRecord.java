// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.player;

import com.petepeterepeat.seasons.core.port.PlayerId;
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
    OptionalInt pendingDestination) {
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

  public static PlayerRecord create(PlayerId id, String name) {
    return new PlayerRecord(
        id, name, Map.of(), 0, 0, Map.of(), Map.of(), Set.of(), OptionalInt.empty());
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
        pendingDestination);
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
        pendingDestination);
  }

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
        OptionalInt.of(season));
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
        destination);
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
        pendingDestination);
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
        OptionalInt.empty());
  }

  /** Brought into a season, with the statistics it starts from. */
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
        pendingDestination);
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
        pendingDestination);
  }

  public Optional<Map<String, Long>> baseline(int season) {
    return Optional.ofNullable(baselines.get(season));
  }
}
