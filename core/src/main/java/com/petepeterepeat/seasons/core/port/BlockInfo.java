// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

/**
 * What the core needs to know about one block.
 *
 * @param type a namespaced block id, such as {@code minecraft:lava}
 * @param solid whether a player can stand on it
 * @param passable whether a player can stand in it
 * @param bed whether it is a bed of any kind, straw beds included
 */
public record BlockInfo(String type, boolean solid, boolean passable, boolean bed) {
  public static final BlockInfo AIR = new BlockInfo("minecraft:air", false, true, false);
}
