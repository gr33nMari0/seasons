// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.snapshot;

import com.petepeterepeat.seasons.api.OnlinePlayer;
import com.petepeterepeat.seasons.core.map.Waypoint;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.zone.Zone;
import com.petepeterepeat.seasons.core.zone.ZoneShape;
import java.util.List;
import java.util.Map;

/**
 * The area a player is in, for who's online: the nearest shared waypoint or zone within 200 blocks,
 * or else the biome. Never their coordinates.
 */
public final class Regions {
  /** How far a named place reaches. */
  public static final double REACH = 200;

  private Regions() {}

  /**
   * The area a player stands in.
   *
   * @param waypoints the shared waypoints, by set
   * @param zones the zones in force
   */
  public static OnlinePlayer.Region of(
      Position at, String biome, Map<String, List<Waypoint>> waypoints, List<Zone> zones) {
    double best = Double.POSITIVE_INFINITY;
    OnlinePlayer.Region region = new OnlinePlayer.Region("biome", biome);
    for (List<Waypoint> set : waypoints.values()) {
      for (Waypoint waypoint : set) {
        if (!waypoint.at().dimension().equals(at.dimension())) {
          continue;
        }
        double d = Math.hypot(waypoint.at().x() - at.x(), waypoint.at().z() - at.z());
        if (d <= REACH && d < best) {
          best = d;
          region = new OnlinePlayer.Region("waypoint", waypoint.name());
        }
      }
    }
    for (Zone zone : zones) {
      if (!zone.dimension().equals(at.dimension())) {
        continue;
      }
      double d = distance(zone.shape(), at);
      if (d <= REACH && d < best) {
        best = d;
        region = new OnlinePlayer.Region("zone", zone.displayName());
      }
    }
    return region;
  }

  /** How far a position is from a zone's shape across the ground; 0 inside it. */
  static double distance(ZoneShape shape, Position at) {
    if (shape.contains(at.blockX(), at.blockY(), at.blockZ())) {
      return 0;
    }
    return switch (shape) {
      case ZoneShape.Box box -> {
        double dx = Math.max(Math.max(box.minX() - at.x(), 0), at.x() - (box.maxX() + 1.0));
        double dz = Math.max(Math.max(box.minZ() - at.z(), 0), at.z() - (box.maxZ() + 1.0));
        yield Math.hypot(dx, dz);
      }
      case ZoneShape.Cylinder c ->
          Math.max(0, Math.hypot(at.x() - c.centerX(), at.z() - c.centerZ()) - c.radius());
      default -> Double.POSITIVE_INFINITY;
    };
  }
}
