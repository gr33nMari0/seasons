// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.port.Position;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The rules galleries keep (see Validation rules in the spec): galleries don't overlap, cells sit
 * inside their gallery and don't overlap each other, and anchors sit inside their cell. A cell with
 * no figure anchor is allowed, as a cell still being tagged is; it is never handed out.
 */
public final class GalleryRules {
  /** The most blocks a gate plane may have. */
  public static final long MAX_GATE = 64;

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
    // One egg plinth per season, across every museum.
    java.util.Map<Integer, String> eggs = new java.util.HashMap<>();
    for (Gallery gallery : galleries) {
      for (Cell cell : gallery.cells()) {
        for (Anchor anchor : cell.anchors()) {
          if (anchor.egg().isEmpty()) {
            continue;
          }
          String where = gallery.id() + ":" + cell.id();
          String was = eggs.putIfAbsent(anchor.egg().getAsInt(), where);
          if (was != null) {
            problems.add(
                new Problem(
                    gallery.id(),
                    "cells." + cell.id() + ".anchors",
                    "Season " + anchor.egg().getAsInt() + " already has its egg plinth at " + was));
          }
        }
      }
    }
    return problems;
  }

  /**
   * What a cell's gate plane would be set over: any anchor but its own gate corners, and the
   * gallery's entry, exits and launch button (questions 169).
   */
  private static List<String> covered(Gallery gallery, Cell gated, Bounds plane) {
    List<String> found = new ArrayList<>();
    for (Cell cell : gallery.cells()) {
      for (Anchor anchor : cell.anchors()) {
        if (cell.id().equals(gated.id()) && anchor.type() == AnchorType.GATE) {
          continue;
        }
        if (plane.contains(anchor.at())) {
          found.add(
              "the " + anchor.type().id() + " anchor of " + cell.id() + " at " + xyz(anchor.at()));
        }
      }
    }
    gallery
        .entry()
        .filter(plane::contains)
        .ifPresent(at -> found.add("the gallery's entry at " + xyz(at)));
    for (Position exit : gallery.exits()) {
      if (plane.contains(exit)) {
        found.add("an exit at " + xyz(exit));
      }
    }
    gallery
        .launch()
        .filter(plane::contains)
        .ifPresent(at -> found.add("the launch button at " + xyz(at)));
    return found;
  }

  private static String xyz(Position at) {
    return at.blockX() + ", " + at.blockY() + ", " + at.blockZ();
  }

  /**
   * Whether an anchor sits where its cell's bounds allow: inside them, or for a gate corner, inside
   * or right beside them, in the doorway (questions 250).
   */
  public static boolean fits(Bounds bounds, Anchor anchor) {
    if (anchor.area().isPresent() && !bounds.contains(anchor.area().get())) {
      return false;
    }
    return anchor.type() == AnchorType.GATE
        ? bounds.grown(1).contains(anchor.at())
        : bounds.contains(anchor.at());
  }

  /**
   * The rules the Hall's own parts keep with the galleries (questions 243): the hub overlaps no
   * gallery, the Hall spawn sits inside the hub, and no two links share a block.
   */
  public static List<Problem> check(HallLayout hall, Collection<Gallery> galleries) {
    List<Problem> problems = new ArrayList<>();
    if (hall.hub().isPresent()) {
      Bounds hub = hall.hub().get();
      for (Gallery gallery : galleries) {
        if (gallery.bounds().map(hub::overlaps).orElse(false)) {
          problems.add(new Problem(HALL, "hub", "overlaps gallery " + gallery.id()));
        }
      }
    }
    if (hall.spawn().isPresent() && !hall.inHub(hall.spawn().get())) {
      problems.add(new Problem(HALL, "spawn", "the Hall spawn must sit inside the hub"));
    }
    if (hall.areas().entry().isPresent()
        && !hall.hub().map(h -> h.contains(hall.areas().entry().get())).orElse(false)) {
      problems.add(new Problem(HALL, "spawn.to", "the Hall spawn's box must sit inside the hub"));
    }
    List<HallLayout.Link> seen = new ArrayList<>();
    for (HallLayout.Link link : hall.links().values()) {
      for (HallLayout.Link other : seen) {
        if (other.isAt(link.block())) {
          problems.add(
              new Problem(HALL, "links." + link.id(), "shares its block with link " + other.id()));
        }
      }
      seen.add(link);
    }
    return problems;
  }

  /** The name {@link Problem#gallery()} has for {@code hall.yml}'s problems. */
  public static final String HALL = "hall";

  /** The rules one gallery keeps by itself. */
  public static List<Problem> check(Gallery gallery) {
    List<Problem> problems = new ArrayList<>();
    if (gallery.areas().entry().isPresent()
        && !gallery.bounds().map(b -> b.contains(gallery.areas().entry().get())).orElse(false)) {
      problems.add(
          new Problem(gallery.id(), "entry.to", "the entry's box must sit inside the gallery"));
    }
    if (gallery.launch().isPresent() && !gallery.contains(gallery.launch().get())) {
      problems.add(
          new Problem(gallery.id(), "launch", "the launch button must sit inside the gallery"));
    }
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
          if (anchor.egg().isPresent() && gallery.kind() != Gallery.Kind.MUSEUM) {
            problems.add(
                new Problem(
                    gallery.id(), path + ".anchors", "only a museum can have an egg plinth"));
          }
          if (!fits(bounds, anchor)) {
            problems.add(
                new Problem(
                    gallery.id(),
                    path + ".anchors",
                    anchor.type() == AnchorType.GATE
                        ? "a gate corner must sit inside the cell's bounds or right beside them"
                        : "the "
                            + anchor.type().id()
                            + " anchor must sit inside the cell's bounds"));
          }
        }
      }
      if (cell.anchors(AnchorType.GATE).size() == 1) {
        problems.add(
            new Problem(
                gallery.id(),
                path + ".anchors",
                "a gate needs two gate anchors, at opposite corners of its plane"));
      }
      Optional<Bounds> gate = cell.gate();
      if (gate.isPresent()) {
        Bounds plane = gate.get();
        boolean thin =
            plane.minX() == plane.maxX()
                || plane.minY() == plane.maxY()
                || plane.minZ() == plane.maxZ();
        if (!thin) {
          problems.add(
              new Problem(
                  gallery.id(), path + ".anchors", "the gate must be a plane one block thick"));
        } else if (plane.volume() > MAX_GATE) {
          problems.add(
              new Problem(
                  gallery.id(),
                  path + ".anchors",
                  "the gate is "
                      + plane.volume()
                      + " blocks; at most "
                      + MAX_GATE
                      + " are allowed"));
        }
        if (gallery.bounds().isPresent() && !gallery.bounds().get().contains(plane)) {
          problems.add(
              new Problem(
                  gallery.id(),
                  path + ".anchors",
                  "the gate must sit inside the gallery's bounds"));
        }
        for (Cell other : gallery.cells()) {
          if (!other.id().equals(cell.id())
              && other.bounds().map(b -> b.overlaps(plane)).orElse(false)) {
            problems.add(
                new Problem(
                    gallery.id(), path + ".anchors", "the gate reaches into cell " + other.id()));
          }
        }
        for (String what : covered(gallery, cell, plane)) {
          problems.add(
              new Problem(
                  gallery.id(),
                  path + ".anchors",
                  "the gate's plane holds " + what + ", which the gate would be set over"));
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
