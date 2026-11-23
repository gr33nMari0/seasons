// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.api;

import java.util.Optional;
import java.util.UUID;

/**
 * A player online, as the latest snapshot saw them. Never their coordinates.
 *
 * @param skin their skin's texture address on Mojang's texture server, if known
 * @param dimension such as {@code minecraft:overworld}
 * @param region the area they're in
 * @param afk whether they have been idle past the server's AFK time
 */
public record OnlinePlayer(
    UUID id, String name, Optional<String> skin, String dimension, Region region, boolean afk) {

  /**
   * The area a player is in: the nearest named place within 200 blocks, or else the biome.
   *
   * @param kind {@code waypoint}, {@code zone} or {@code biome}
   * @param name the place's name, or the biome's id such as {@code minecraft:plains}
   */
  public record Region(String kind, String name) {}
}
