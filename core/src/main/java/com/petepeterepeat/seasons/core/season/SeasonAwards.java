// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.season;

import com.petepeterepeat.seasons.core.port.PlayerId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One season's awards (see Stats, season cards and awards in the spec), first worked out when its
 * Intermission began, in the order config.yml listed them then, and worked out again when a late
 * archive of the season changes them. An award nobody earned is kept with no winners, so the season
 * shows it was given to nobody.
 *
 * @param at when they were last worked out
 * @param counted the players whose archives they were worked out from
 * @param pending what is still to be done since they were last worked out; empty once done
 */
public record SeasonAwards(
    int season, Instant at, List<Award> awards, Set<PlayerId> counted, Optional<Pending> pending) {
  public SeasonAwards {
    awards = List.copyOf(awards);
    counted = Set.copyOf(counted);
  }

  /** Awards with nothing left to do and no archives recorded as counted. */
  public SeasonAwards(int season, Instant at, List<Award> awards) {
    this(season, at, awards, Set.of(), Optional.empty());
  }

  /**
   * What is left to do after the awards were worked out: drawing them, copying them into archives
   * and announcing them. Kept in {@code season.json}, so a restart finishes it.
   *
   * @param first whether this is the first time the season's awards were worked out, which is
   *     announced in full with the firsts recap; otherwise the change is announced
   * @param players whose cells and archives change: every winner before and after
   * @param announced whether the announcement is made, so only copying into archives is left
   */
  public record Pending(boolean first, Set<PlayerId> players, boolean announced) {
    public Pending {
      players = Set.copyOf(players);
    }
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

  /** The same awards with what is left to do. */
  public SeasonAwards withPending(Optional<Pending> next) {
    return new SeasonAwards(season, at, awards, counted, next);
  }

  /** The same awards, worked out from these archives' players. */
  public SeasonAwards withCounted(Set<PlayerId> next) {
    return new SeasonAwards(season, at, awards, next, pending);
  }
}
