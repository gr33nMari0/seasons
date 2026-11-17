// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import com.petepeterepeat.seasons.core.event.IntentHandler;

/**
 * Turns platform events into intents and applies the core's decision. The core never sees a
 * platform event.
 */
public interface Events {
  /** Sets the handler every intent goes to. Until one is set, everything is allowed. */
  void handle(IntentHandler handler);
}
