// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.season;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SeasonMachineTest {
  private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
  private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

  private static SeasonState active(Stage stage) {
    return SeasonState.start(2, stage, Optional.empty());
  }

  private static SeasonState changed(Optional<SeasonState> state, SeasonEvent event) {
    Transition transition = SeasonMachine.apply(state, event, NOW);
    assertThat(transition).isInstanceOf(Changed.class);
    return ((Changed) transition).state();
  }

  private static String refused(Optional<SeasonState> state, SeasonEvent event) {
    Transition transition = SeasonMachine.apply(state, event, NOW);
    assertThat(transition).isInstanceOf(Refused.class);
    return ((Refused) transition).messageKey();
  }

  @Test
  void initRecordsTheSeasonInProgressInTheOpenStage() {
    SeasonState state = changed(Optional.empty(), new Init(2));

    assertThat(state.season()).isEqualTo(2);
    assertThat(state.phase()).isEqualTo(new Active(Stage.OPEN));
  }

  @Test
  void initIsRefusedOnceASeasonIsRecorded() {
    assertThat(refused(Optional.of(active(Stage.OPEN)), new Init(3))).isEqualTo("init.refused");
    assertThat(refused(Optional.empty(), new Init(0))).isEqualTo("init.invalid");
  }

  @Test
  void everythingElseNeedsASeason() {
    assertThat(refused(Optional.empty(), new End(TEN_MINUTES))).isEqualTo("season.none");
    assertThat(refused(Optional.empty(), new Cancel())).isEqualTo("season.none");
  }

  @Test
  void endStartsACountdownFromTheCurrentStage() {
    SeasonState state = changed(Optional.of(active(Stage.FRONTIER)), new End(TEN_MINUTES));

    assertThat(state.phase()).isEqualTo(new Countdown(Stage.FRONTIER, NOW, NOW.plus(TEN_MINUTES)));
  }

  @Test
  void endNowGoesStraightToArchiving() {
    assertThat(changed(Optional.of(active(Stage.OPEN)), new End(Duration.ZERO)).phase())
        .isEqualTo(new Archiving());
  }

  @Test
  void aSecondEndIsRefused() {
    SeasonState counting = changed(Optional.of(active(Stage.OPEN)), new End(TEN_MINUTES));
    SeasonState archiving = changed(Optional.of(counting), new CountdownElapsed());
    SeasonState intermission = changed(Optional.of(archiving), new AllArchived());

    assertThat(refused(Optional.of(counting), new End(TEN_MINUTES)))
        .isEqualTo("end.already-ending");
    assertThat(refused(Optional.of(counting), new End(Duration.ZERO)))
        .isEqualTo("end.already-ending");
    assertThat(refused(Optional.of(archiving), new End(TEN_MINUTES)))
        .isEqualTo("end.already-ending");
    assertThat(refused(Optional.of(intermission), new End(TEN_MINUTES)))
        .isEqualTo("end.already-ended");
  }

  @Test
  void cancelRestoresTheStateBeforeTheEnd() {
    SeasonState before =
        active(Stage.FRONTIER)
            .withPlannedEnd(Optional.of(ZonedDateTime.parse("2026-12-05T20:00Z")), NOW);
    SeasonState counting = changed(Optional.of(before), new End(TEN_MINUTES));

    assertThat(changed(Optional.of(counting), new Cancel())).isEqualTo(before);
  }

  @Test
  void cancelIsRefusedOutsideTheCountdown() {
    SeasonState archiving = changed(Optional.of(active(Stage.OPEN)), new End(Duration.ZERO));

    assertThat(refused(Optional.of(archiving), new Cancel())).isEqualTo("cancel.too-late");
    assertThat(refused(Optional.of(active(Stage.OPEN)), new Cancel())).isEqualTo("cancel.nothing");
  }

  @Test
  void theCountdownLeadsToArchivingThenTheIntermission() {
    SeasonState counting = changed(Optional.of(active(Stage.OPEN)), new End(TEN_MINUTES));
    SeasonState archiving = changed(Optional.of(counting), new CountdownElapsed());

    assertThat(archiving.phase()).isEqualTo(new Archiving());
    assertThat(changed(Optional.of(archiving), new AllArchived()).phase())
        .isEqualTo(new Intermission());
    assertThat(refused(Optional.of(active(Stage.OPEN)), new AllArchived()))
        .isEqualTo("end.not-archiving");
  }

  @Test
  void launchStartsTheNextSeasonInTheFrontierStage() {
    SeasonState intermission = active(Stage.OPEN).withPhase(new Intermission());

    SeasonState launched = changed(Optional.of(intermission), new Launch());

    assertThat(launched.season()).isEqualTo(3);
    assertThat(launched.phase()).isEqualTo(new Active(Stage.FRONTIER));
    assertThat(launched.launchedAt()).contains(NOW);
    assertThat(launched.plannedEnd()).isEmpty();
    assertThat(refused(Optional.of(active(Stage.OPEN)), new Launch())).isEqualTo("launch.refused");
  }

  @Test
  void homecomingOpensTheFrontierOnce() {
    SeasonState open = changed(Optional.of(active(Stage.FRONTIER)), new Homecoming());

    assertThat(open.phase()).isEqualTo(new Active(Stage.OPEN));
    assertThat(open.homecomingAt()).contains(NOW);
    assertThat(refused(Optional.of(open), new Homecoming())).isEqualTo("homecoming.refused");
  }

  @Test
  void aRestartCancelsARunningCountdownAndRecordsIt() {
    SeasonState counting =
        changed(Optional.of(active(Stage.OPEN).withAutoEndStarted()), new End(TEN_MINUTES));

    SeasonState restarted = changed(Optional.of(counting), new Restarted());

    assertThat(restarted.phase()).isEqualTo(new Active(Stage.OPEN));
    assertThat(restarted.countdownInterrupted()).contains(NOW);
    assertThat(restarted.autoEndStarted()).isFalse();
    assertThat(changed(Optional.of(restarted), new End(TEN_MINUTES)).countdownInterrupted())
        .isEmpty();
  }

  @Test
  void aRestartLeavesOtherPhasesAlone() {
    SeasonState archiving = changed(Optional.of(active(Stage.OPEN)), new End(Duration.ZERO));

    assertThat(changed(Optional.of(archiving), new Restarted())).isEqualTo(archiving);
  }

  @Test
  void schedulingResetsRemindersAndOnlyWorksWhileActive() {
    SeasonState sent =
        active(Stage.OPEN)
            .withPlannedEnd(Optional.of(ZonedDateTime.parse("2026-12-05T20:00Z")), NOW)
            .withRemindersSent(Set.of(Duration.ofDays(7)));
    ZonedDateTime later = ZonedDateTime.parse("2026-12-12T20:00-05:00");

    SeasonState rescheduled = changed(Optional.of(sent), new ScheduleEnd(Optional.of(later)));

    assertThat(rescheduled.plannedEnd()).contains(later);
    assertThat(rescheduled.plannedEndSetAt()).contains(NOW);
    assertThat(rescheduled.remindersSent()).isEmpty();
    assertThat(
            refused(
                Optional.of(sent.withPhase(new Intermission())),
                new ScheduleEnd(Optional.of(later))))
        .isEqualTo("schedule.refused");
  }
}
