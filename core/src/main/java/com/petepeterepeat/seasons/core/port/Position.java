// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

/**
 * A position in a dimension, as stored in every Seasons file.
 *
 * @param dimension a namespaced dimension id, such as {@code minecraft:overworld}
 */
public record Position(String dimension, double x, double y, double z, float yaw, float pitch) {
  public static Position of(String dimension, double x, double y, double z) {
    return new Position(dimension, x, y, z, 0f, 0f);
  }

  public int blockX() {
    return (int) Math.floor(x);
  }

  public int blockY() {
    return (int) Math.floor(y);
  }

  public int blockZ() {
    return (int) Math.floor(z);
  }
}
