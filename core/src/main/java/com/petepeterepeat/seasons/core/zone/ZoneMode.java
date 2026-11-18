// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.zone;

import java.util.Locale;
import java.util.Optional;

/** What a zone stops, in order of strength: where zones overlap, the strongest mode applies. */
public enum ZoneMode {
  /** Nothing. */
  OFF,
  /** Nothing, but entering is broadcast once a day. */
  ANNOUNCE,
  /** Building, breaking, buckets and TNT; explosions and fire break nothing. */
  NO_BUILD,
  /** Entering, and changing or taking anything inside from outside its edge. */
  BLOCK;

  /** As files and commands write it: {@code no-build}. */
  public String id() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  /** A mode by its id, such as {@code no-build}. */
  public static Optional<ZoneMode> of(String id) {
    for (ZoneMode mode : values()) {
      if (mode.id().equals(id.trim().toLowerCase(Locale.ROOT))) {
        return Optional.of(mode);
      }
    }
    return Optional.empty();
  }

  /** Whether nothing inside breaks or burns: blocked or no-build. */
  public boolean protects() {
    return this == BLOCK || this == NO_BUILD;
  }
}
