// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.player;

import com.petepeterepeat.seasons.core.season.Phase;
import com.petepeterepeat.seasons.core.season.SeasonState;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * What happens when a player logs in (On login in the spec). Pure: a record, whether the server had
 * their player file before, and the season state in; a plan out.
 */
public final class LoginPlan {
  private LoginPlan() {}

  /** One login's plan. */
  public sealed interface Plan {
    /**
     * Archive them into a season they finished but weren't archived into.
     *
     * @param thenEnter whether the next season has launched since, so they go straight on to its
     *     spawn; otherwise they go to their cell, if they have one
     */
    record Archive(int season, boolean thenEnter) implements Plan {}

    /** Already archived, but a launch happened while they were away: on to the new spawn. */
    record EnterSeason(int season) implements Plan {}

    /** Archived during the Intermission, and their cell is still waiting for them. */
    record ToCell(int season) implements Plan {}

    /** Never played here: a record, and the season spawn. */
    record NewPlayer(int season) implements Plan {}

    /** Nothing to do but play. */
    record Play() implements Plan {}
  }

  public static Plan decide(
      Optional<PlayerRecord> record, boolean playedBefore, SeasonState state) {
    int current = state.season();
    boolean launched = state.launchedAt().isPresent();
    boolean between = isBetweenSeasons(state.phase());

    OptionalInt target = OptionalInt.empty();
    if (record.isPresent()) {
      PlayerRecord r = record.get();
      target =
          r.seasons().keySet().stream()
              .filter(season -> season > r.archivedThrough())
              // What they carry belongs to the latest season they entered: never archive it into
              // an earlier one, as after unarchiving an old season.
              .filter(season -> season >= r.seasonEntered())
              .filter(season -> finished(season, state))
              .mapToInt(Integer::intValue)
              .max();
    } else if (playedBefore && state.initSeason().isPresent()) {
      // Played before Seasons recorded anything: they played the season /season init recorded.
      int init = state.initSeason().get();
      if (finished(init, state)) {
        target = OptionalInt.of(init);
      }
    }
    if (target.isPresent()) {
      return new Plan.Archive(target.getAsInt(), !between);
    }
    if (record.isEmpty() && !playedBefore) {
      return new Plan.NewPlayer(current);
    }
    if (record.isPresent()) {
      PlayerRecord r = record.get();
      if (between && r.pendingDestination().isPresent()) {
        return new Plan.ToCell(r.pendingDestination().getAsInt());
      }
      if (!between && launched && r.seasonEntered() < current) {
        return new Plan.EnterSeason(current);
      }
    }
    return new Plan.Play();
  }

  /** Whether a season is over: an earlier one, or this one once it is ending or ended. */
  public static boolean finished(int season, SeasonState state) {
    return season < state.season() || (season == state.season() && isBetweenSeasons(state.phase()));
  }

  /** Archiving or the Intermission: the season is over and the next hasn't launched. */
  public static boolean isBetweenSeasons(Phase phase) {
    return phase instanceof Phase.Archiving || phase instanceof Phase.Intermission;
  }
}
