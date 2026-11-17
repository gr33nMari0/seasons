// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.event;

import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;

/** Something a player is about to do, raised by an adapter from a platform event. */
public sealed interface Intent {
  PlayerId player();

  /** Breaking the block at a position. */
  record BreakBlock(PlayerId player, Position block) implements Intent {}

  /**
   * Placing a block at a position.
   *
   * @param blockType a namespaced block id, such as {@code minecraft:hopper}
   */
  record PlaceBlock(PlayerId player, Position block, String blockType) implements Intent {}

  /** Any click or drag in any open menu, including the player's own inventory. */
  record UseInventory(PlayerId player) implements Intent {}

  /** Dropping an item. */
  record DropItem(PlayerId player) implements Intent {}

  /** Picking up an item from the ground. */
  record PickupItem(PlayerId player) implements Intent {}

  /** Swapping the main hand and off hand. */
  record SwapHands(PlayerId player) implements Intent {}

  /** Eating or drinking. */
  record Consume(PlayerId player) implements Intent {}

  /** Using an item, or a block or entity, with either hand. */
  record Interact(PlayerId player) implements Intent {}

  /** Taking damage of any kind. */
  record TakeDamage(PlayerId player) implements Intent {}
}
