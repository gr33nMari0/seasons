// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.map;

import com.petepeterepeat.seasons.core.port.Position;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Everything Seasons shows on the map at one moment, in no web map's own types: built on the server
 * thread from the files and the season state, and handed to a {@link MapPublisher}. Two models are
 * equal when they show the same.
 *
 * @param label the name of Seasons' own marker set
 * @param points the season spawns, the spawn candidates, the Hall and the museum
 * @param areas the zones, as they are in force now
 * @param waypointSets the shared waypoints, each set a marker set of its own
 */
public record MapModel(
    String label, List<Point> points, List<Area> areas, List<WaypointSet> waypointSets) {
  public MapModel {
    points = List.copyOf(points);
    areas = List.copyOf(areas);
    waypointSets = List.copyOf(waypointSets);
  }

  /**
   * A point on the map.
   *
   * @param id unique within its set, and the same from one model to the next
   */
  public record Point(String id, String label, Position at) {}

  /** How an area looks, from what its zone stops now. */
  public enum Style {
    /** Blocked: nobody enters or changes anything. */
    LOCKED,
    /** No building. */
    NO_BUILD,
    /** Entering is announced. */
    ANNOUNCE
  }

  /** An outline across x and z, in block coordinates. */
  public sealed interface Outline {
    /** A rectangle between two corners, at the edges of the blocks it covers. */
    record Rectangle(double minX, double minZ, double maxX, double maxZ) implements Outline {}

    /** A circle around a centre. */
    record Circle(double centerX, double centerZ, double radius) implements Outline {}

    /**
     * A ring beyond a rectangle or circle: an {@code outside} zone, drawn out to {@code outer}
     * rather than the edge of the world, so web maps and the mods that copy their areas can show it
     * (questions 158). A web map draws {@code outer} with {@code inside} as a hole.
     */
    record Beyond(Outline inside, Outline outer) implements Outline {
      /** The least a ring reaches beyond its inside, in blocks. */
      public static final double MIN_REACH = 5000;

      /**
       * The ring around {@code inside} that reaches {@code max(2 × radius, 5000)} blocks beyond it,
       * where a rectangle's radius is half its longer side: a circle around a circle, a rectangle
       * around a rectangle.
       */
      public static Beyond around(Outline inside) {
        return switch (inside) {
          case Circle c ->
              new Beyond(c, new Circle(c.centerX(), c.centerZ(), c.radius() + reach(c.radius())));
          case Rectangle r -> {
            double reach = reach(Math.max(r.maxX() - r.minX(), r.maxZ() - r.minZ()) / 2);
            yield new Beyond(
                r,
                new Rectangle(
                    r.minX() - reach, r.minZ() - reach, r.maxX() + reach, r.maxZ() + reach));
          }
          case Beyond _ -> throw new IllegalArgumentException("A ring is beyond a circle or box");
        };
      }

      private static double reach(double radius) {
        return Math.max(2 * radius, MIN_REACH);
      }
    }
  }

  /**
   * An area: a zone's outline between two heights, with holes where nothing is ever enforced.
   *
   * @param minY the bottom, at the bottom edge of the lowest block covered
   * @param maxY the top, at the top edge of the highest block covered
   */
  public record Area(
      String id,
      String label,
      String dimension,
      Outline outline,
      List<Outline> holes,
      double minY,
      double maxY,
      Style style) {
    public Area {
      holes = List.copyOf(holes);
    }
  }

  /**
   * A set of shared waypoints.
   *
   * @param id the set's id, such as {@code megaprojects}
   */
  public record WaypointSet(String id, String label, List<Point> points) {
    public WaypointSet {
      points = List.copyOf(points);
    }
  }

  /** Every dimension something is shown in, by id. */
  public Set<String> dimensions() {
    Set<String> dimensions = new TreeSet<>();
    points.forEach(p -> dimensions.add(p.at().dimension()));
    areas.forEach(a -> dimensions.add(a.dimension()));
    waypointSets.forEach(s -> s.points().forEach(p -> dimensions.add(p.at().dimension())));
    return dimensions;
  }
}
