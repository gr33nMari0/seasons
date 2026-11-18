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

  /**
   * Eating or drinking.
   *
   * @param item a namespaced item id, such as {@code minecraft:chorus_fruit}
   */
  record Consume(PlayerId player, String item) implements Intent {}

  /**
   * Right-clicking with an item in hand, at the air or at a block.
   *
   * @param item a namespaced item id, such as {@code minecraft:ender_pearl}
   */
  record UseItem(PlayerId player, String item) implements Intent {}

  /** Using an item, or a block or entity, with either hand. */
  record Interact(PlayerId player) implements Intent {}

  /**
   * Right-clicking a block, such as an archive anchor.
   *
   * @param mainHand whether this is the main hand's click; the game also raises one for the off
   *     hand
   * @param sneaking whether the player is sneaking
   * @param emptyHand whether the main hand is empty
   * @param bed whether the block is a bed of any kind
   */
  record UseBlock(
      PlayerId player,
      Position block,
      boolean mainHand,
      boolean sneaking,
      boolean emptyHand,
      boolean bed)
      implements Intent {
    public UseBlock(PlayerId player, Position block, boolean mainHand) {
      this(player, block, mainHand, false, false, false);
    }
  }

  /**
   * Interacting with one of Seasons' own displays, such as a figure. The adapter always cancels the
   * interaction itself.
   *
   * @param tag the tag the display was spawned with
   */
  record UseDisplay(PlayerId player, String tag) implements Intent {}

  /** Taking damage of any kind. */
  record TakeDamage(PlayerId player) implements Intent {}

  /**
   * Moving from one block to another by the player's own movement: walking, flying, falling or
   * being pushed. Raised only when the block changes.
   */
  record Move(PlayerId player, Position from, Position to) implements Intent {}

  /** Being teleported. */
  record Teleport(PlayerId player, Position from, Position to, TeleportKind kind)
      implements Intent {}

  /** What moved a player in a {@link Teleport}. */
  enum TeleportKind {
    /** An ender pearl landing. */
    PEARL,
    /** Chorus fruit. */
    CHORUS,
    /** Seasons itself, such as /hof or launch. */
    SEASONS,
    /** Anything else: commands, other plugins, portals. */
    OTHER
  }

  /**
   * Getting on an entity: riding or sitting.
   *
   * @param cushion whether the entity is a cushion
   */
  record Mount(PlayerId player, boolean cushion) implements Intent {}
}
