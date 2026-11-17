// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.time.Instant;
import java.util.Map;

/**
 * A player's advancements at one moment.
 *
 * @param awarded every awarded criterion, with when, by advancement id
 * @param completed visible advancements the player has completed
 * @param visible advancements shown in the advancement screen: those with a display that isn't
 *     hidden. Recipe unlocks have no display.
 */
public record AdvancementSnapshot(
    Map<String, Map<String, Instant>> awarded, int completed, int visible) {
  public AdvancementSnapshot {
    awarded = Map.copyOf(awarded);
  }
}
