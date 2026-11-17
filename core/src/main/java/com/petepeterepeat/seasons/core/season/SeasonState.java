// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.season;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.Set;

/**
 * {@code data/season.json}: the server-wide season state. Changed only through {@link
 * SeasonMachine}, on the server thread.
 *
 * @param plannedEnd a copy of the season file's planned end, which is the source of truth
 * @param plannedEndSetAt when that planned end was set; reminders already past then are never sent
 * @param remindersSent reminders already sent for this planned end, by how long before it they are
 * @param autoEndStarted whether {@code schedule.autoEnd} already started the countdown for this
 *     planned end, so a cancelled automatic countdown doesn't start again
 * @param countdownInterrupted when a restart cancelled a running countdown, until the next end
 */
public record SeasonState(
    int season,
    Phase phase,
    Optional<ZonedDateTime> plannedEnd,
    Optional<Instant> plannedEndSetAt,
    Set<Duration> remindersSent,
    boolean autoEndStarted,
    Optional<Instant> launchedAt,
    Optional<Instant> homecomingAt,
    Optional<Instant> countdownInterrupted) {
  public SeasonState {
    remindersSent = Set.copyOf(remindersSent);
  }

  /** A season recorded with {@code /season init} or started at launch. */
  public static SeasonState start(int season, Stage stage, Optional<Instant> launchedAt) {
    return new SeasonState(
        season,
        new Phase.Active(stage),
        Optional.empty(),
        Optional.empty(),
        Set.of(),
        false,
        launchedAt,
        Optional.empty(),
        Optional.empty());
  }

  public SeasonState withPhase(Phase next) {
    return new SeasonState(
        season,
        next,
        plannedEnd,
        plannedEndSetAt,
        remindersSent,
        autoEndStarted,
        launchedAt,
        homecomingAt,
        countdownInterrupted);
  }

  /** A new planned end, or none: reminders and the automatic end start over. */
  public SeasonState withPlannedEnd(Optional<ZonedDateTime> end, Instant now) {
    return new SeasonState(
        season,
        phase,
        end,
        end.map(e -> now),
        Set.of(),
        false,
        launchedAt,
        homecomingAt,
        countdownInterrupted);
  }

  public SeasonState withRemindersSent(Set<Duration> sent) {
    return new SeasonState(
        season,
        phase,
        plannedEnd,
        plannedEndSetAt,
        sent,
        autoEndStarted,
        launchedAt,
        homecomingAt,
        countdownInterrupted);
  }

  public SeasonState withAutoEndStarted() {
    return new SeasonState(
        season,
        phase,
        plannedEnd,
        plannedEndSetAt,
        remindersSent,
        true,
        launchedAt,
        homecomingAt,
        countdownInterrupted);
  }

  public SeasonState withHomecoming(Instant at) {
    return new SeasonState(
        season,
        phase,
        plannedEnd,
        plannedEndSetAt,
        remindersSent,
        autoEndStarted,
        launchedAt,
        Optional.of(at),
        countdownInterrupted);
  }

  public SeasonState withCountdownInterrupted(Optional<Instant> at) {
    return new SeasonState(
        season,
        phase,
        plannedEnd,
        plannedEndSetAt,
        remindersSent,
        autoEndStarted,
        launchedAt,
        homecomingAt,
        at);
  }
}
