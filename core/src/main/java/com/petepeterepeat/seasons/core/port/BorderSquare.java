// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

/**
 * A world border as one player sees it: a square across x and z, in block coordinates.
 *
 * @param size the length of each side
 */
public record BorderSquare(double centerX, double centerZ, double size) {
  /** Whether a position's x and z lie within the square. */
  public boolean holds(Position at) {
    double half = size / 2;
    return Math.abs(at.x() - centerX) <= half && Math.abs(at.z() - centerZ) <= half;
  }
}
