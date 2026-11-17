// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.season;

import com.petepeterepeat.seasons.core.season.Phase.Active;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Which planned-end reminder is due. A reminder goes out once per planned end, only if its moment
 * was still ahead when the planned end was set, and only while the season is Active. When several
 * fall due at once (the server was down), only the nearest is sent.
 */
public final class Reminders {
  private Reminders() {}

  /**
   * A reminder to send now.
   *
   * @param before how long before the planned end it is, such as 7 days
   * @param sent every reminder to mark as sent, including skipped ones
   */
  public record Due(Duration before, ZonedDateTime plannedEnd, Set<Duration> sent) {}

  public static Optional<Due> due(SeasonState state, List<Duration> reminders, Instant now) {
    if (!(state.phase() instanceof Active)
        || state.plannedEnd().isEmpty()
        || state.plannedEndSetAt().isEmpty()) {
      return Optional.empty();
    }
    ZonedDateTime end = state.plannedEnd().get();
    Instant endInstant = end.toInstant();
    Instant setAt = state.plannedEndSetAt().get();
    if (!now.isBefore(endInstant)) {
      return Optional.empty();
    }
    Duration nearest = null;
    Set<Duration> sent = new HashSet<>(state.remindersSent());
    for (Duration reminder : reminders) {
      Instant at = endInstant.minus(reminder);
      if (state.remindersSent().contains(reminder) || !at.isAfter(setAt) || now.isBefore(at)) {
        continue;
      }
      sent.add(reminder);
      if (nearest == null || reminder.compareTo(nearest) < 0) {
        nearest = reminder;
      }
    }
    return nearest == null ? Optional.empty() : Optional.of(new Due(nearest, end, sent));
  }

  /**
   * When {@code schedule.autoEnd} should start the countdown: the planned end, or 5 minutes after
   * startup if the server was down at the planned end. Empty once it has started for this planned
   * end, or with no planned end.
   */
  public static Optional<Instant> autoEndAt(SeasonState state, Instant serverStarted) {
    if (state.autoEndStarted() || state.plannedEnd().isEmpty()) {
      return Optional.empty();
    }
    Instant end = state.plannedEnd().get().toInstant();
    return Optional.of(
        end.isAfter(serverStarted) ? end : serverStarted.plus(Duration.ofMinutes(5)));
  }
}
