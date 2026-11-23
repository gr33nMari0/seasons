// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What Seasons knows of a player across seasons.
 *
 * @param name the last name seen
 * @param skin their skin's texture address on Mojang's texture server, if known
 * @param seasons the seasons they played, oldest first
 * @param leaderboardHidden whether they hide themselves from leaderboards
 */
public record PlayerProfile(
    UUID id,
    String name,
    Optional<String> skin,
    List<PlayedSeason> seasons,
    boolean leaderboardHidden) {
  public PlayerProfile {
    seasons = List.copyOf(seasons);
  }

  /**
   * One season played.
   *
   * @param cell their Hall of Fame cell for it, as {@code gallery/cell}, if they have one
   * @param awards the ids of the awards they won, such as {@code most-deaths}
   * @param firsts the season firsts they earned
   * @param statistics the season's difference of every General-tab statistic, by id; for the season
   *     in progress, as of now
   */
  public record PlayedSeason(
      int season,
      Instant firstSeen,
      Instant lastSeen,
      Duration playTime,
      Optional<String> cell,
      List<String> awards,
      List<First> firsts,
      Map<String, Long> statistics) {
    public PlayedSeason {
      awards = List.copyOf(awards);
      firsts = List.copyOf(firsts);
      statistics = Map.copyOf(statistics);
    }
  }

  /**
   * A season first.
   *
   * @param key what was reached, such as {@code nether}
   * @param label how it reads, such as {@code reached the Nether}
   */
  public record First(String key, String label, Instant at) {}
}
