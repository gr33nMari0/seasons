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
}
