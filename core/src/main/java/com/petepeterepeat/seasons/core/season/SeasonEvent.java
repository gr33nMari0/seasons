// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.season;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Optional;

/** Everything that can move the season state. */
public sealed interface SeasonEvent {
  /** {@code /season init <season>}: record the season already in progress. */
  record Init(int season) implements SeasonEvent {}

  /** {@code /season schedule end}, or a reload that found a different planned end. */
  record ScheduleEnd(Optional<ZonedDateTime> at) implements SeasonEvent {}

  /**
   * {@code /season end}, or the automatic end.
   *
   * @param countdown how long the countdown runs; zero skips it ({@code --now})
   */
  record End(Duration countdown) implements SeasonEvent {}

  /** {@code /season cancel}. */
  record Cancel() implements SeasonEvent {}

  /** The countdown reached zero. */
  record CountdownElapsed() implements SeasonEvent {}

  /** Every online player was archived. */
  record AllArchived() implements SeasonEvent {}

  /** {@code /season launch}. */
  record Launch() implements SeasonEvent {}

  /** {@code /season unlock homecoming}. */
  record Homecoming() implements SeasonEvent {}

  /** The server started with this state on disk. */
  record Restarted() implements SeasonEvent {}
}
