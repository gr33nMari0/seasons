// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.season;

import com.petepeterepeat.seasons.core.port.PlayerId;
import java.time.Instant;
import java.util.List;

/**
 * One season's awards (see Stats, season cards and awards in the spec), worked out once when its
 * Intermission began, in the order config.yml listed them then. An award nobody earned is kept with
 * no winners, so the season shows it was given to nobody.
 *
 * @param at when they were worked out
 */
public record SeasonAwards(int season, Instant at, List<Award> awards) {
  public SeasonAwards {
    awards = List.copyOf(awards);
  }

  /**
   * One award.
   *
   * @param id its id in config.yml, such as {@code most-deaths}
   * @param measure what it counted, as config.yml wrote it
   * @param value the winning value, such as the deaths or centimetres travelled
   * @param winners everyone with that value, by name; empty if the best was 0
   */
  public record Award(String id, String measure, long value, List<Winner> winners) {
    public Award {
      winners = List.copyOf(winners);
    }

    public boolean wonBy(PlayerId player) {
      return winners.stream().anyMatch(w -> w.player().equals(player));
    }
  }

  /**
   * A winner.
   *
   * @param name their name at archive time
   */
  public record Winner(PlayerId player, String name) {}

  /** The awards a player won, in order. */
  public List<Award> wonBy(PlayerId player) {
    return awards.stream().filter(a -> a.wonBy(player)).toList();
  }

  /** The awards someone won, in order. */
  public List<Award> given() {
    return awards.stream().filter(a -> !a.winners().isEmpty()).toList();
  }
}
