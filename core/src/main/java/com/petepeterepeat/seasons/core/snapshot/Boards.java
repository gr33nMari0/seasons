// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.snapshot;

import com.petepeterepeat.seasons.api.LeaderboardEntry;
import com.petepeterepeat.seasons.api.Leaderboards;
import com.petepeterepeat.seasons.api.PlayerName;
import com.petepeterepeat.seasons.core.hall.SeasonCard;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToLongFunction;

/** A season's leaderboards from each player's statistics for it. */
public final class Boards {
  private static final long TICKS_PER_SECOND = 20;
  private static final long CM_PER_BLOCK = 100;

  private Boards() {}

  /**
   * The three leaderboards.
   *
   * @param statistics each player's season difference of the General-tab statistics
   * @param hidden players who hide themselves from leaderboards
   */
  public static Leaderboards of(
      int season, Map<PlayerName, Map<String, Long>> statistics, Set<UUID> hidden) {
    return new Leaderboards(
        season,
        board(statistics, hidden, s -> s.getOrDefault("minecraft:deaths", 0L)),
        board(
            statistics, hidden, s -> s.getOrDefault("minecraft:play_time", 0L) / TICKS_PER_SECOND),
        board(
            statistics,
            hidden,
            s ->
                SeasonCard.TRAVEL.stream().mapToLong(id -> s.getOrDefault(id, 0L)).sum()
                    / CM_PER_BLOCK));
  }

  private static List<LeaderboardEntry> board(
      Map<PlayerName, Map<String, Long>> statistics,
      Set<UUID> hidden,
      ToLongFunction<Map<String, Long>> measure) {
    return statistics.entrySet().stream()
        .map(e ->
            new LeaderboardEntry(
                e.getKey(),
                Math.max(0, measure.applyAsLong(e.getValue())),
                hidden.contains(e.getKey().id())))
        .sorted(
            Comparator.comparingLong(LeaderboardEntry::value)
                .reversed()
                .thenComparing(e -> e.player().name()))
        .toList();
  }
}
