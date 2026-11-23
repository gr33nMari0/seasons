// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.api;

import java.util.List;

/**
 * A season's leaderboards, each best first.
 *
 * @param deaths deaths in the season
 * @param playTime seconds played in the season
 * @param distance blocks travelled in the season, on foot, swimming, flying and riding
 */
public record Leaderboards(
    int season,
    List<LeaderboardEntry> deaths,
    List<LeaderboardEntry> playTime,
    List<LeaderboardEntry> distance) {
  public Leaderboards {
    deaths = List.copyOf(deaths);
    playTime = List.copyOf(playTime);
    distance = List.copyOf(distance);
  }
}
