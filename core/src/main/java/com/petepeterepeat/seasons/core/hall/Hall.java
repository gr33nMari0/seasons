// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

/** Everything archiving needs from the Hall of Fame. M1 part 3 supplies the real ones. */
public record Hall(
    Galleries galleries, CellDirectory cells, DisplayRenderer displays, HallBounds bounds) {
  /** No Hall yet: nothing to lock, no cells, nothing to draw, nothing inside. */
  public static Hall none() {
    return new Hall(
        Galleries.none(), CellDirectory.none(), DisplayRenderer.none(), HallBounds.none());
  }
}
