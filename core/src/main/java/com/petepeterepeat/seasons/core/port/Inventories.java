// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.util.List;

/** Reads, clears and restores what a player carries, as neutral item records. */
public interface Inventories {
  /** Copies everything the player carries. Call on the player's thread. */
  InventorySnapshot snapshot(PlayerId player);

  /**
   * Closes any open menu and returns the cursor and crafting-grid items to the inventory. Items
   * with no room left are taken off the cursor and grid and returned, never dropped.
   */
  List<NeutralItem> settle(PlayerId player);

  /**
   * Empties inventory, armour, off hand, cursor and crafting grid, and the ender chest if asked.
   */
  void clear(PlayerId player, boolean enderChest);

  /** Puts a snapshot back into the player's slots, replacing what is there. */
  void restore(PlayerId player, InventorySnapshot snapshot);

  /**
   * Deletes every item tagged as archived from the player's inventory and ender chest.
   *
   * @return what was deleted, such as "3 × minecraft:stone"
   */
  List<String> purgeArchived(PlayerId player);

  /** The game's data version, which items written now carry as {@code DataVersion}. */
  int dataVersion();
}
