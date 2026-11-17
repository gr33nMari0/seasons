// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

/**
 * A player's experience.
 *
 * @param level the level shown on the bar
 * @param progress how far through the level, from 0 to 1
 * @param total every point collected, as the game counts it
 */
public record Experience(int level, float progress, int total) {
  public static final Experience NONE = new Experience(0, 0f, 0);
}
