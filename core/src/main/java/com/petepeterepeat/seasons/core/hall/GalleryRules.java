// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The rules galleries keep (see Validation rules in the spec): galleries don't overlap, cells sit
 * inside their gallery and don't overlap each other, and anchors sit inside their cell. A cell with
 * no figure anchor is allowed, as a cell still being tagged is; it is never handed out.
 */
public final class GalleryRules {
  private GalleryRules() {}

  /**
   * One broken rule.
   *
   * @param gallery the gallery whose file has the problem
   * @param path the key path in that file, such as {@code cells.c1.bounds}
   */
  public record Problem(String gallery, String path, String message) {}

  /** Every rule broken by a set of galleries. */
  public static List<Problem> check(Collection<Gallery> galleries) {
    List<Problem> problems = new ArrayList<>();
    List<Gallery> seen = new ArrayList<>();
    for (Gallery gallery : galleries) {
      for (Gallery other : seen) {
        if (gallery.bounds().isPresent()
            && other.bounds().isPresent()
            && gallery.bounds().get().overlaps(other.bounds().get())) {
          problems.add(new Problem(gallery.id(), "bounds", "overlaps gallery " + other.id()));
        }
      }
      seen.add(gallery);
      problems.addAll(check(gallery));
    }
    return problems;
  }

  /** The rules one gallery keeps by itself. */
  public static List<Problem> check(Gallery gallery) {
    List<Problem> problems = new ArrayList<>();
    List<Cell> seen = new ArrayList<>();
    for (Cell cell : gallery.cells()) {
      String path = "cells." + cell.id();
      if (cell.bounds().isPresent()) {
        Bounds bounds = cell.bounds().get();
        if (gallery.bounds().isEmpty() || !gallery.bounds().get().contains(bounds)) {
          problems.add(
              new Problem(gallery.id(), path + ".bounds", "must sit inside the gallery's bounds"));
        }
        for (Cell other : seen) {
          if (other.bounds().isPresent() && other.bounds().get().overlaps(bounds)) {
            problems.add(
                new Problem(gallery.id(), path + ".bounds", "overlaps cell " + other.id()));
          }
        }
        for (Anchor anchor : cell.anchors()) {
          if (!bounds.contains(anchor.at())) {
            problems.add(
                new Problem(
                    gallery.id(),
                    path + ".anchors",
                    "the " + anchor.type().id() + " anchor must sit inside the cell's bounds"));
          }
        }
      }
      for (AnchorType type : AnchorType.values()) {
        if (cell.anchors(type).size() > type.max()) {
          problems.add(
              new Problem(
                  gallery.id(),
                  path + ".anchors",
                  "has more " + type.id() + " anchors than the " + type.max() + " allowed"));
        }
      }
      seen.add(cell);
    }
    return problems;
  }
}
