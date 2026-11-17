// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

/**
 * What archiving resets besides items and advancements. Fire, health and hunger are always reset.
 *
 * @param xp clear levels and points
 * @param effects clear every potion effect
 * @param respawnPoint clear the bed or anchor respawn point
 */
public record ResetPlan(boolean xp, boolean effects, boolean respawnPoint) {}
