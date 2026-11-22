// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.scenario;

import com.petepeterepeat.seasons.core.port.PlayerId;

/**
 * Called as archiving passes each {@link CrashPoint}. On a real server it does nothing; on a
 * scenario server it can stop the server dead there (docs/adr/0003-scenario-hooks.md).
 */
@FunctionalInterface
public interface CrashPoints {
  /** Does nothing: every real server. */
  CrashPoints NONE = (point, player) -> {};

  void reached(CrashPoint point, PlayerId player);

  /**
   * Whether a crash point has stopped the server, which in a test may carry on after the halt: work
   * a crash would never run, such as putting a failed archive back, is skipped then.
   */
  default boolean halted() {
    return false;
  }
}
