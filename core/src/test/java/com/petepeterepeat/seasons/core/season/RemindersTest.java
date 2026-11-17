// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.season;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RemindersTest {
  private static final List<Duration> REMINDERS =
      List.of(Duration.ofDays(7), Duration.ofDays(1), Duration.ofHours(1), Duration.ofMinutes(10));
  private static final Instant SET_AT = Instant.parse("2026-11-18T12:00:00Z");
  private static final ZonedDateTime END = ZonedDateTime.parse("2026-12-05T20:00-05:00");

  private static SeasonState planned() {
    return SeasonState.start(2, Stage.OPEN, Optional.empty())
        .withPlannedEnd(Optional.of(END), SET_AT);
  }

  private static Instant before(Duration duration) {
    return END.toInstant().minus(duration);
  }

  @Test
  void nothingIsDueBeforeTheFirstReminder() {
    assertThat(Reminders.due(planned(), REMINDERS, before(Duration.ofDays(8)))).isEmpty();
  }

  @Test
  void eachReminderFallsDueAtItsMomentOnce() {
    Optional<Reminders.Due> due = Reminders.due(planned(), REMINDERS, before(Duration.ofDays(7)));

    assertThat(due)
        .get()
        .satisfies(d -> {
          assertThat(d.before()).isEqualTo(Duration.ofDays(7));
          assertThat(d.plannedEnd()).isEqualTo(END);
          assertThat(d.sent()).containsExactly(Duration.ofDays(7));
        });
    SeasonState sent = planned().withRemindersSent(due.get().sent());
    assertThat(Reminders.due(sent, REMINDERS, before(Duration.ofDays(6)))).isEmpty();
    assertThat(Reminders.due(sent, REMINDERS, before(Duration.ofDays(1))))
        .get()
        .extracting(Reminders.Due::before)
        .isEqualTo(Duration.ofDays(1));
  }

  @Test
  void afterDowntimeOnlyTheNearestIsSentAndTheRestAreSkipped() {
    Optional<Reminders.Due> due =
        Reminders.due(planned(), REMINDERS, before(Duration.ofMinutes(30)));

    assertThat(due)
        .get()
        .satisfies(d -> {
          assertThat(d.before()).isEqualTo(Duration.ofHours(1));
          assertThat(d.sent())
              .containsExactlyInAnyOrder(
                  Duration.ofDays(7), Duration.ofDays(1), Duration.ofHours(1));
        });
  }

  @Test
  void remindersAlreadyPastWhenTheEndWasSetAreNeverSent() {
    SeasonState late =
        SeasonState.start(2, Stage.OPEN, Optional.empty())
            .withPlannedEnd(Optional.of(END), before(Duration.ofMinutes(5)));

    assertThat(Reminders.due(late, REMINDERS, before(Duration.ofMinutes(1)))).isEmpty();
  }

  @Test
  void nothingAtOrAfterThePlannedEndOrOutsideActive() {
    assertThat(Reminders.due(planned(), REMINDERS, END.toInstant())).isEmpty();
    assertThat(
            Reminders.due(
                planned().withPhase(new Phase.Intermission()),
                REMINDERS,
                before(Duration.ofDays(7))))
        .isEmpty();
    assertThat(
            Reminders.due(
                SeasonState.start(2, Stage.OPEN, Optional.empty()),
                REMINDERS,
                before(Duration.ofDays(7))))
        .isEmpty();
  }

  @Test
  void autoEndIsAtThePlannedEndOrFiveMinutesAfterALateStart() {
    assertThat(Reminders.autoEndAt(planned(), SET_AT)).contains(END.toInstant());
    Instant lateStart = END.toInstant().plus(Duration.ofHours(2));
    assertThat(Reminders.autoEndAt(planned(), lateStart))
        .contains(lateStart.plus(Duration.ofMinutes(5)));
    assertThat(Reminders.autoEndAt(planned().withAutoEndStarted(), SET_AT)).isEmpty();
    assertThat(Reminders.autoEndAt(planned().withRemindersSent(Set.of()), SET_AT)).isPresent();
  }
}
