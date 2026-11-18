// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

/**
 * How a player is moving, as the server sees it. Whether they stand on the ground is worked out
 * from the blocks under them, not from what the client says.
 *
 * @param riding on any entity or vehicle
 * @param gliding with an elytra
 * @param swimming swimming, or in water at all
 * @param burning on fire
 */
public record Movement(boolean riding, boolean gliding, boolean swimming, boolean burning) {
  public static final Movement STILL = new Movement(false, false, false, false);
}
