// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.season;

/** What {@link SeasonMachine} made of an event. */
public sealed interface Transition {
  /** The event applies; this is the new state. */
  record Changed(SeasonState state) implements Transition {}

  /**
   * The event doesn't apply now.
   *
   * @param messageKey the messages.yml key explaining why; its placeholder is {@code <season>}
   */
  record Refused(String messageKey) implements Transition {}
}
