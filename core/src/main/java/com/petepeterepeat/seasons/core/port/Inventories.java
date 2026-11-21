// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import com.petepeterepeat.seasons.core.message.Message;
import java.util.List;
import java.util.Optional;

/**
 * Reads, clears and restores what a player carries, and reads containers, as neutral item records.
 */
public interface Inventories {
  /** Copies everything the player carries. Call on the player's thread. */
  InventorySnapshot snapshot(PlayerId player);

  /**
   * Returns the cursor and crafting-grid items to the inventory, then closes any open menu. Items
   * with no room left stay where they are, on the player, and the menu stays open so closing it
   * can't drop them; {@link #clear} takes them off later. Nothing leaves the player here.
   *
   * @return the items left on the cursor and grid, as {@link #loose} would
   */
  List<NeutralItem> settle(PlayerId player);

  /** What is on the player's cursor and crafting grid right now. */
  List<NeutralItem> loose(PlayerId player);

  /**
   * Empties inventory, armour, off hand, cursor and crafting grid, and the ender chest if asked,
   * then closes any open menu.
   */
  void clear(PlayerId player, boolean enderChest);

  /**
   * Gives an online player a whole stack in their main inventory, or nothing at all if it doesn't
   * fit: never part of it, and nothing is dropped. Call on the player's thread.
   *
   * @return whether it was given
   */
  boolean give(PlayerId player, NeutralItem item);

  /**
   * Gives an online player the Seasons wand (questions 226): one of {@code item}, named and marked
   * {@code seasons:wand}, which the adapter raises {@code UseWand} for. Nothing if it doesn't fit.
   *
   * @return whether it was given
   */
  boolean giveWand(PlayerId player, String item, Message name, Message lore);

  /** Whether an item carries the {@code seasons:archived} tag, as copies shown in the Hall do. */
  boolean isArchived(NeutralItem item);

  /** Puts a snapshot back into the player's slots, replacing what is there. */
  void restore(PlayerId player, InventorySnapshot snapshot);

  /**
   * Deletes every item tagged as archived from the player's inventory and ender chest.
   *
   * @return what was deleted, such as "3 × minecraft:stone"
   */
  List<String> purgeArchived(PlayerId player);

  /**
   * What a container block holds, slot by slot, if the block holds items at all: a chest (both
   * halves of a double chest), a barrel, a shulker box, a decorated pot. Empty if it isn't loaded
   * or holds no items.
   */
  Optional<List<Optional<NeutralItem>>> container(Position block);

  /** The game's data version, which items written now carry as {@code DataVersion}. */
  int dataVersion();

  /**
   * The item ids an online player carries: inventory, armour, off hand and cursor, such as {@code
   * minecraft:diamond}. For season firsts.
   */
  java.util.Set<String> carriedItemIds(PlayerId player);
}
