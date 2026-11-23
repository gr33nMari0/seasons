// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.api;

import java.util.List;
import java.util.Optional;

/** The Hall of Fame cells for one season, never with their positions. */
public record GalleryView(int season, List<CellView> cells) {
  public GalleryView {
    cells = List.copyOf(cells);
  }

  /**
   * A cell.
   *
   * @param owner whoever it belongs to for the season, with their name then
   * @param awards the ids of the awards its owner won that season
   */
  public record CellView(
      String gallery, String cell, Optional<PlayerName> owner, List<String> awards) {
    public CellView {
      awards = List.copyOf(awards);
    }
  }
}
