// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import java.util.Optional;

/**
 * The tag on each display Seasons draws in a cell, {@code gallery/cell/season/kind}, so a redraw
 * finds the old ones and a click on a figure finds its cell.
 */
record DisplayTag(String gallery, String cell, int season, String kind) {
  static final String FIGURE = "figure";
  static final String PLAQUE = "plaque";
  static final String TROPHY = "trophy";

  /** The prefix every display of one cell's season starts with. */
  static String prefix(String gallery, String cell, int season) {
    return gallery + "/" + cell + "/" + season + "/";
  }

  @Override
  public String toString() {
    return prefix(gallery, cell, season) + kind;
  }

  static Optional<DisplayTag> parse(String tag) {
    String[] parts = tag.split("/", -1);
    if (parts.length != 4) {
      return Optional.empty();
    }
    try {
      return Optional.of(new DisplayTag(parts[0], parts[1], Integer.parseInt(parts[2]), parts[3]));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }
}
