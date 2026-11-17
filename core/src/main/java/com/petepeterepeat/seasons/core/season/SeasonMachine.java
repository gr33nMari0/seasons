// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.season;

import com.petepeterepeat.seasons.core.season.Phase.Active;
import com.petepeterepeat.seasons.core.season.Phase.Archiving;
import com.petepeterepeat.seasons.core.season.Phase.Countdown;
import com.petepeterepeat.seasons.core.season.Phase.Intermission;
import com.petepeterepeat.seasons.core.season.SeasonEvent.AllArchived;
import com.petepeterepeat.seasons.core.season.SeasonEvent.Cancel;
import com.petepeterepeat.seasons.core.season.SeasonEvent.CountdownElapsed;
import com.petepeterepeat.seasons.core.season.SeasonEvent.End;
import com.petepeterepeat.seasons.core.season.SeasonEvent.Homecoming;
import com.petepeterepeat.seasons.core.season.SeasonEvent.Init;
import com.petepeterepeat.seasons.core.season.SeasonEvent.Launch;
import com.petepeterepeat.seasons.core.season.SeasonEvent.Restarted;
import com.petepeterepeat.seasons.core.season.SeasonEvent.ScheduleEnd;
import com.petepeterepeat.seasons.core.season.Transition.Changed;
import com.petepeterepeat.seasons.core.season.Transition.Refused;
import java.time.Instant;
import java.util.Optional;

/**
 * The season lifecycle as a pure function: a state and an event in, a new state or a refusal out.
 * Every rule about which command works when lives here.
 *
 * <pre>
 * (none) --init--> Active(Open)
 * Active --end--> Countdown --elapsed--> Archiving --all archived--> Intermission
 * Active --end --now--> Archiving
 * Countdown --cancel or restart--> Active(stage before); a restart also lets autoEnd start again
 * Intermission --launch--> Active(Frontier) of the next season
 * Active(Frontier) --homecoming--> Active(Open)
 * </pre>
 */
public final class SeasonMachine {
  private SeasonMachine() {}

  public static Transition apply(Optional<SeasonState> current, SeasonEvent event, Instant now) {
    if (event instanceof Init init) {
      if (current.isPresent()) {
        return new Refused("init.refused");
      }
      if (init.season() < 1) {
        return new Refused("init.invalid");
      }
      return new Changed(SeasonState.init(init.season()));
    }
    if (current.isEmpty()) {
      return new Refused("season.none");
    }
    SeasonState state = current.get();
    Phase phase = state.phase();
    return switch (event) {
      case Init init -> throw new AssertionError("handled above");
      case ScheduleEnd schedule ->
          phase instanceof Active
              ? new Changed(state.withPlannedEnd(schedule.at(), now))
              : new Refused("schedule.refused");
      case End end ->
          switch (phase) {
            case Active active -> {
              Phase next =
                  end.countdown().isPositive()
                      ? new Countdown(active.stage(), now, now.plus(end.countdown()))
                      : new Archiving();
              yield new Changed(state.withPhase(next).withCountdownInterrupted(Optional.empty()));
            }
            case Countdown countdown -> new Refused("end.already-ending");
            case Archiving archiving -> new Refused("end.already-ending");
            case Intermission intermission -> new Refused("end.already-ended");
          };
      case Cancel cancel ->
          switch (phase) {
            case Countdown countdown ->
                new Changed(state.withPhase(new Active(countdown.stageBefore())));
            case Archiving archiving -> new Refused("cancel.too-late");
            case Active active -> new Refused("cancel.nothing");
            case Intermission intermission -> new Refused("cancel.nothing");
          };
      case CountdownElapsed elapsed ->
          phase instanceof Countdown
              ? new Changed(state.withPhase(new Archiving()))
              : new Refused("end.not-counting");
      case AllArchived archived ->
          phase instanceof Archiving
              ? new Changed(state.withPhase(new Intermission()))
              : new Refused("end.not-archiving");
      case Launch launch ->
          phase instanceof Intermission
              ? new Changed(state.next(now))
              : new Refused("launch.refused");
      case Homecoming homecoming ->
          phase instanceof Active active && active.stage() == Stage.FRONTIER
              ? new Changed(state.withPhase(new Active(Stage.OPEN)).withHomecoming(now))
              : new Refused("homecoming.refused");
      case Restarted restarted ->
          phase instanceof Countdown countdown
              ? new Changed(
                  new SeasonState(
                      state.season(),
                      new Active(countdown.stageBefore()),
                      state.plannedEnd(),
                      state.plannedEndSetAt(),
                      state.remindersSent(),
                      false,
                      state.launchedAt(),
                      state.homecomingAt(),
                      Optional.of(now),
                      state.initSeason(),
                      state.launchApplied()))
              : new Changed(state);
    };
  }
}
