// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.port.Position;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;

/** Converts between core positions and Bukkit locations. */
final class PaperPositions {
  private PaperPositions() {}

  static Position toPosition(Location location) {
    World world = location.getWorld();
    return new Position(
        world.getKey().asString(),
        location.getX(),
        location.getY(),
        location.getZ(),
        location.getYaw(),
        location.getPitch());
  }

  static Optional<World> world(String dimension) {
    NamespacedKey key = NamespacedKey.fromString(dimension);
    return key == null ? Optional.empty() : Optional.ofNullable(Bukkit.getWorld(key));
  }

  static Location toLocation(Position position) {
    World world =
        world(position.dimension())
            .orElseThrow(
                () -> new IllegalArgumentException("No dimension " + position.dimension()));
    return new Location(
        world, position.x(), position.y(), position.z(), position.yaw(), position.pitch());
  }
}
