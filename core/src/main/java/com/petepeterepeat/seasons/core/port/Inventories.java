// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

/** Reads, clears and restores what a player carries, as neutral item records. */
public interface Inventories {
  /** Copies everything the player carries. Call on the player's thread. */
  InventorySnapshot snapshot(PlayerId player);

  /** Empties inventory, armour, off hand, cursor, crafting grid and ender chest. */
  void clear(PlayerId player);

  /** Puts a snapshot back into the player's slots, replacing what is there. */
  void restore(PlayerId player, InventorySnapshot snapshot);
}
