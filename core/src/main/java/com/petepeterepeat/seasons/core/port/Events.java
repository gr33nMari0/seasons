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

  /** Sets who hears about joins and leaves. */
  void sessions(Sessions sessions);

  /**
   * Sets who hears about tripwire hits. The adapter deletes any item tagged as archived that is
   * picked up, spawned in the world or left in an inventory when a menu closes, then reports it.
   */
  void tripwire(TripwireReport report);

  /** Sets who hears about completed advancements and changes of dimension, for season firsts. */
  void milestones(Milestones milestones);
}
