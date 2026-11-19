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
     * Everything beyond a rectangle or circle, out to the edge of the world: an {@code outside}
     * zone. A web map draws it as the world with {@code inside} as a hole.
     */
    record Beyond(Outline inside) implements Outline {
      /** How far the world reaches from 0, 0 along x and z, whatever its border is set to. */
      public static final double WORLD_EDGE = 29_999_984;
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
