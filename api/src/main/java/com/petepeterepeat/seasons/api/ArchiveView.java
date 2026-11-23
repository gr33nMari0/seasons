// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What a player was archived with at the end of a season, as text: never a real item.
 *
 * @param player who, with their name when archived
 * @param cell their cell for the season, as {@code gallery/cell}, if they have one
 * @param items each stack with where it was: {@code armour}, {@code offhand}, {@code storage},
 *     {@code ender} and so on
 */
public record ArchiveView(
    PlayerName player,
    int season,
    Instant created,
    Optional<String> cell,
    List<String> awards,
    List<PlayerProfile.First> firsts,
    List<ItemView> items) {
  public ArchiveView {
    awards = List.copyOf(awards);
    firsts = List.copyOf(firsts);
    items = List.copyOf(items);
  }

  /**
   * One stack, described from its item data.
   *
   * @param section where it was, such as {@code armour}
   * @param id the item id, such as {@code minecraft:diamond_sword}
   * @param name its custom name as plain text, if renamed
   * @param enchantments its enchantments, in the order stored
   * @param trim its armour trim, if any
   */
  public record ItemView(
      String section,
      String id,
      int count,
      Optional<String> name,
      List<Enchantment> enchantments,
      Optional<Trim> trim) {
    public ItemView {
      enchantments = List.copyOf(enchantments);
    }
  }

  /** An enchantment, such as {@code minecraft:sharpness} at level 5. */
  public record Enchantment(String id, int level) {}

  /** An armour trim, such as pattern {@code minecraft:coast} in {@code minecraft:gold}. */
  public record Trim(String pattern, String material) {}
}
