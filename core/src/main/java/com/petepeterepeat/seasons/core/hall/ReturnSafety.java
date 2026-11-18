// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.port.BlockInfo;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Whether a spot is safe to send a player back to: a solid block to stand on that isn't magma, no
 * lava, fire or powder snow at feet or head, room to stand, and no monster within the hostile
 * radius. Also finds the nearest safe spot within a radius.
 */
public final class ReturnSafety {
  /** How far around a saved spot the nearest safe one is looked for. */
  public static final int NEARBY = 8;

  private ReturnSafety() {}

  /**
   * Why a spot isn't safe, as a key under {@code hof.unsafe}, or empty if it is.
   *
   * @param hostiles where the monsters near it are
   */
  public static Optional<String> unsafe(
      Position spot,
      Function<Position, BlockInfo> blocks,
      List<Position> hostiles,
      int hostileRadius) {
    String dimension = spot.dimension();
    BlockInfo below =
        blocks.apply(Position.of(dimension, spot.blockX(), spot.blockY() - 1, spot.blockZ()));
    BlockInfo feet =
        blocks.apply(Position.of(dimension, spot.blockX(), spot.blockY(), spot.blockZ()));
    BlockInfo head =
        blocks.apply(Position.of(dimension, spot.blockX(), spot.blockY() + 1, spot.blockZ()));
    for (BlockInfo block : List.of(feet, head, below)) {
      Optional<String> hazard = EntryChecks.hazardOf(block.type());
      if (hazard.isPresent()) {
        return hazard;
      }
    }
    if (below.type().equals("minecraft:magma_block")) {
      return Optional.of("magma");
    }
    if (!feet.passable() || !head.passable()) {
      return Optional.of("no-room");
    }
    if (!below.solid()) {
      return Optional.of("no-floor");
    }
    double reach = (double) hostileRadius * hostileRadius;
    for (Position monster : hostiles) {
      if (monster.dimension().equals(dimension) && distanceSquared(monster, spot) <= reach) {
        return Optional.of("monsters");
      }
    }
    return Optional.empty();
  }

  /**
   * The nearest safe spot within {@link #NEARBY} blocks of {@code around}, standing in the middle
   * of its block and facing as {@code around} does.
   *
   * @param hostiles the monsters within {@link #NEARBY} plus the hostile radius of {@code around}
   */
  public static Optional<Position> nearest(
      Position around,
      Function<Position, BlockInfo> blocks,
      List<Position> hostiles,
      int hostileRadius) {
    List<Position> candidates = new ArrayList<>();
    for (int dx = -NEARBY; dx <= NEARBY; dx++) {
      for (int dy = -NEARBY; dy <= NEARBY; dy++) {
        for (int dz = -NEARBY; dz <= NEARBY; dz++) {
          if (dx * dx + dy * dy + dz * dz > NEARBY * NEARBY) {
            continue;
          }
          candidates.add(
              new Position(
                  around.dimension(),
                  around.blockX() + dx + 0.5,
                  around.blockY() + dy,
                  around.blockZ() + dz + 0.5,
                  around.yaw(),
                  around.pitch()));
        }
      }
    }
    candidates.sort(Comparator.comparingDouble(c -> distanceSquared(c, around)));
    for (Position candidate : candidates) {
      if (unsafe(candidate, blocks, hostiles, hostileRadius).isEmpty()) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  private static double distanceSquared(Position a, Position b) {
    double dx = a.x() - b.x();
    double dy = a.y() - b.y();
    double dz = a.z() - b.z();
    return dx * dx + dy * dy + dz * dz;
  }
}
