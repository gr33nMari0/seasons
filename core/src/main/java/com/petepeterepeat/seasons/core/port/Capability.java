// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

/**
 * Something an adapter may or may not be able to do. The core degrades where one is missing, and
 * {@code /season status} lists the gaps.
 */
public enum Capability {
  /** Mannequin figures. Without it, figures fall back to armour stands with a player head. */
  MANNEQUINS,
  /** A world border shown to one player only, for the optional frontier outline. */
  PER_PLAYER_WORLD_BORDER,
  /** The platform runs regions on separate threads (Folia). */
  REGION_THREADING,
  /** The platform reports every world change, including those made by other plugins or mods. */
  FULL_PROTECTION_COVERAGE
}
