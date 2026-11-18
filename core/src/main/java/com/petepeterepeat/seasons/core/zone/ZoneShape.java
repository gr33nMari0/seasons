// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.zone;

import java.util.OptionalInt;

/**
 * Where a zone is, in whole blocks: a block is inside or not, and a player is inside when the block
 * they stand in is.
 */
public sealed interface ZoneShape {
  /** Whether the block at these coordinates is inside. */
  boolean contains(int x, int y, int z);

  /** The smallest x a block inside can have; likewise the others, for indexing by chunk. */
  int minX();

  int maxX();

  int minZ();

  int maxZ();

  /** A box, both corners included. */
  record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) implements ZoneShape {
    public Box {
      if (minX > maxX || minY > maxY || minZ > maxZ) {
        throw new IllegalArgumentException("A box's minimum corner can't be past its maximum");
      }
    }

    /** The box between two corners given in any order. */
    public static Box between(int x1, int y1, int z1, int x2, int y2, int z2) {
      return new Box(
          Math.min(x1, x2),
          Math.min(y1, y2),
          Math.min(z1, z2),
          Math.max(x1, x2),
          Math.max(y1, y2),
          Math.max(z1, z2));
    }

    @Override
    public boolean contains(int x, int y, int z) {
      return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }
  }

  /**
   * Every block whose middle lies within {@code radius} of the centre across x and z, between
   * {@code minY} and {@code maxY} (both included) where they are set.
   *
   * @param centerX the centre's x, as a coordinate: a centre of 0, 0 is the corner four blocks
   *     share
   */
  record Cylinder(double centerX, double centerZ, double radius, OptionalInt minY, OptionalInt maxY)
      implements ZoneShape {
    public Cylinder {
      if (!(radius > 0)) {
        throw new IllegalArgumentException("A cylinder's radius must be more than 0");
      }
      if (minY.isPresent() && maxY.isPresent() && minY.getAsInt() > maxY.getAsInt()) {
        throw new IllegalArgumentException("A cylinder's minY can't be above its maxY");
      }
    }

    @Override
    public boolean contains(int x, int y, int z) {
      if ((minY.isPresent() && y < minY.getAsInt()) || (maxY.isPresent() && y > maxY.getAsInt())) {
        return false;
      }
      double dx = x + 0.5 - centerX;
      double dz = z + 0.5 - centerZ;
      return dx * dx + dz * dz <= radius * radius;
    }

    @Override
    public int minX() {
      return (int) Math.floor(centerX - radius);
    }

    @Override
    public int maxX() {
      return (int) Math.ceil(centerX + radius);
    }

    @Override
    public int minZ() {
      return (int) Math.floor(centerZ - radius);
    }

    @Override
    public int maxZ() {
      return (int) Math.ceil(centerZ + radius);
    }
  }
}
