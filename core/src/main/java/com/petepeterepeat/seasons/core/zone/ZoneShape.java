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

  /**
   * Whether any block inside lies over a rectangle of columns, both corners included, at some
   * height: for indexing by chunk and for the map. May say yes when the answer is near enough.
   */
  boolean reaches(int minX, int minZ, int maxX, int maxZ);

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

    @Override
    public boolean reaches(int fromX, int fromZ, int toX, int toZ) {
      return toX >= minX && fromX <= maxX && toZ >= minZ && fromZ <= maxZ;
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
    public boolean reaches(int minX, int minZ, int maxX, int maxZ) {
      // The rectangle's point nearest the centre, at the edges of its blocks.
      double x = Math.clamp(centerX, minX, maxX + 1.0);
      double z = Math.clamp(centerZ, minZ, maxZ + 1.0);
      return Math.hypot(x - centerX, z - centerZ) <= radius;
    }
  }

  /**
   * Every block whose middle lies beyond {@code radius} of the centre across x and z, at every
   * height: exactly the blocks a {@link Cylinder} with the same centre and radius and no y limits
   * leaves out.
   *
   * @param centerX the centre's x, as a coordinate, as for a cylinder
   */
  record OutsideCircle(double centerX, double centerZ, double radius) implements ZoneShape {
    public OutsideCircle {
      if (!(radius > 0)) {
        throw new IllegalArgumentException("An outside circle's radius must be more than 0");
      }
    }

    @Override
    public boolean contains(int x, int y, int z) {
      double dx = x + 0.5 - centerX;
      double dz = z + 0.5 - centerZ;
      return dx * dx + dz * dz > radius * radius;
    }

    @Override
    public boolean reaches(int minX, int minZ, int maxX, int maxZ) {
      // The middle of the rectangle's block furthest from the centre.
      double dx = Math.max(Math.abs(minX + 0.5 - centerX), Math.abs(maxX + 0.5 - centerX));
      double dz = Math.max(Math.abs(minZ + 0.5 - centerZ), Math.abs(maxZ + 0.5 - centerZ));
      return dx * dx + dz * dz > radius * radius;
    }
  }

  /** Every column outside a rectangle, both corners of which are inside it, at every height. */
  record OutsideBox(int minX, int minZ, int maxX, int maxZ) implements ZoneShape {
    public OutsideBox {
      if (minX > maxX || minZ > maxZ) {
        throw new IllegalArgumentException("A box's minimum corner can't be past its maximum");
      }
    }

    /** The outside of the rectangle between two corners given in any order. */
    public static OutsideBox between(int x1, int z1, int x2, int z2) {
      return new OutsideBox(Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2));
    }

    @Override
    public boolean contains(int x, int y, int z) {
      return x < minX || x > maxX || z < minZ || z > maxZ;
    }

    @Override
    public boolean reaches(int fromX, int fromZ, int toX, int toZ) {
      return fromX < minX || toX > maxX || fromZ < minZ || toZ > maxZ;
    }
  }

  /** Every block at {@code y} and above, across the whole dimension. */
  record YAbove(int y) implements ZoneShape {
    @Override
    public boolean contains(int x, int y, int z) {
      return y >= this.y;
    }

    @Override
    public boolean reaches(int minX, int minZ, int maxX, int maxZ) {
      return true;
    }
  }

  /** Every block at {@code y} and below, across the whole dimension. */
  record YBelow(int y) implements ZoneShape {
    @Override
    public boolean contains(int x, int y, int z) {
      return y <= this.y;
    }

    @Override
    public boolean reaches(int minX, int minZ, int maxX, int maxZ) {
      return true;
    }
  }
}
