// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.season;

import java.time.Duration;
import java.time.Instant;

/**
 * Where the server is in the lifecycle. Ending has two steps: the boss-bar countdown, which can
 * still be cancelled, then archiving, which can't. Launch is a transition, not a phase.
 */
public sealed interface Phase {
  /** Playing. */
  record Active(Stage stage) implements Phase {}

  /**
   * Ending: the countdown is running.
   *
   * @param stageBefore the stage to go back to if it is cancelled
   */
  record Countdown(Stage stageBefore, Instant startedAt, Instant endsAt) implements Phase {
    public Duration length() {
      return Duration.between(startedAt, endsAt);
    }
  }

  /** Ending: every online player is being archived. */
  record Archiving() implements Phase {}

  /** Between seasons: everyone in the Hall until an admin launches the next one. */
  record Intermission() implements Phase {}
}
