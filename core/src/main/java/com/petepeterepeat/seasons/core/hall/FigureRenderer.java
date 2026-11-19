// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import java.util.Locale;
import java.util.Optional;

/** How a cell's figure is drawn (see Displays in the spec). */
public enum FigureRenderer {
  /** A mannequin in the archived skin. The default. */
  MANNEQUIN,
  /** An armour stand wearing the player's head: the fallback without mannequins. */
  ARMOUR_STAND;

  /** The name in files, such as {@code armour-stand}. */
  public String id() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  public static Optional<FigureRenderer> of(String id) {
    for (FigureRenderer renderer : values()) {
      if (renderer.id().equals(id.toLowerCase(Locale.ROOT))) {
        return Optional.of(renderer);
      }
    }
    return Optional.empty();
  }
}
