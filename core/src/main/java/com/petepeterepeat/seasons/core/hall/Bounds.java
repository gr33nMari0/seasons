// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.port.Position;

/**
 * A box of whole blocks in one dimension, both corners included, such as a gallery's or a cell's
 * bounds.
 */
public record Bounds(String dimension, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
  public Bounds {
    if (minX > maxX || minY > maxY || minZ > maxZ) {
      throw new IllegalArgumentException("A box's minimum corner can't be past its maximum");
    }
  }

  /** The box between two corners given in any order. */
  public static Bounds between(String dimension, int x1, int y1, int z1, int x2, int y2, int z2) {
    return new Bounds(
        dimension,
        Math.min(x1, x2),
        Math.min(y1, y2),
        Math.min(z1, z2),
        Math.max(x1, x2),
        Math.max(y1, y2),
        Math.max(z1, z2));
  }

  /** Whether the block a position is in lies inside. */
  public boolean contains(Position position) {
    return position.dimension().equals(dimension)
        && position.blockX() >= minX
        && position.blockX() <= maxX
        && position.blockY() >= minY
        && position.blockY() <= maxY
        && position.blockZ() >= minZ
        && position.blockZ() <= maxZ;
  }

  /** Whether another box lies wholly inside this one. */
  public boolean contains(Bounds other) {
    return other.dimension.equals(dimension)
        && other.minX >= minX
        && other.maxX <= maxX
        && other.minY >= minY
        && other.maxY <= maxY
        && other.minZ >= minZ
        && other.maxZ <= maxZ;
  }

  public boolean overlaps(Bounds other) {
    return other.dimension.equals(dimension)
        && other.minX <= maxX
        && other.maxX >= minX
        && other.minY <= maxY
        && other.maxY >= minY
        && other.minZ <= maxZ
        && other.maxZ >= minZ;
  }

  /** Every block inside, as positions. */
  public java.util.List<Position> blocks() {
    java.util.List<Position> out = new java.util.ArrayList<>();
    for (int x = minX; x <= maxX; x++) {
      for (int y = minY; y <= maxY; y++) {
        for (int z = minZ; z <= maxZ; z++) {
          out.add(Position.of(dimension, x, y, z));
        }
      }
    }
    return out;
  }

  /** How many blocks it holds. */
  public long volume() {
    return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
  }

  /** The minimum corner, as a position. */
  public Position min() {
    return Position.of(dimension, minX, minY, minZ);
  }

  /** The maximum corner, as a position. */
  public Position max() {
    return Position.of(dimension, maxX, maxY, maxZ);
  }

  /** Such as "20, -61, 20 to 44, -50, 30". */
  public String describe() {
    return minX + ", " + minY + ", " + minZ + " to " + maxX + ", " + maxY + ", " + maxZ;
  }
}
