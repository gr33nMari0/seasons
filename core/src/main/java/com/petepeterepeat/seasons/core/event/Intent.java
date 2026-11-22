// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.event;

import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.List;

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

  /**
   * Picking up an item from the ground.
   *
   * @param item where the item lies, which may be just across a zone's edge
   */
  record PickupItem(PlayerId player, Position item) implements Intent {}

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
   * @param interactable whether right-clicking the block does something, as a door or lever does
   */
  record UseBlock(
      PlayerId player,
      Position block,
      boolean mainHand,
      boolean sneaking,
      boolean emptyHand,
      boolean bed,
      boolean interactable)
      implements Intent {
    public UseBlock(PlayerId player, Position block, boolean mainHand) {
      this(player, block, mainHand, false, false, false, true);
    }

    public UseBlock(
        PlayerId player,
        Position block,
        boolean mainHand,
        boolean sneaking,
        boolean emptyHand,
        boolean bed) {
      this(player, block, mainHand, sneaking, emptyHand, bed, true);
    }
  }

  /**
   * Clicking a block with the Seasons wand in the main hand (questions 226). The adapter raises
   * this instead of its usual intents; a decision to allow it means the click wasn't Seasons', so
   * the adapter goes on as if no wand were held.
   *
   * @param face the side of the block clicked: {@code up}, {@code down}, {@code north}, {@code
   *     south}, {@code east} or {@code west}
   * @param left whether it is a left click
   */
  record UseWand(PlayerId player, Position block, String face, boolean left, boolean sneaking)
      implements Intent {}

  /**
   * Swap-hands pressed with the Seasons wand in the main hand: toggles its mode (questions 272).
   */
  record WandSwap(PlayerId player) implements Intent {}

  /**
   * The hotbar slot changed, a scroll, with the Seasons wand in the main hand: a plaque looked at
   * while sneaking grows or shrinks (questions 278).
   */
  record WandScroll(PlayerId player, int from, int to, boolean sneaking) implements Intent {}

  /**
   * Opening the inventory of a block, such as a chest.
   *
   * @param blocks every block whose inventory opens: one, or both halves of a double chest
   */
  record OpenContainer(PlayerId player, List<Position> blocks) implements Intent {
    public OpenContainer {
      blocks = List.copyOf(blocks);
    }

    public OpenContainer(PlayerId player, Position block) {
      this(player, List.of(block));
    }
  }

  /**
   * A chest just placed joined another into a double chest.
   *
   * @param blocks both halves
   */
  record JoinContainers(PlayerId player, List<Position> blocks) implements Intent {
    public JoinContainers {
      blocks = List.copyOf(blocks);
    }
  }

  /**
   * Changing a block other than by placing or breaking it: lighting it, bone meal, stripping or
   * waxing it.
   *
   * @param how a namespaced id for what does it, such as {@code minecraft:flint_and_steel}
   */
  record ChangeBlock(PlayerId player, Position block, String how) implements Intent {}

  /**
   * Filling or emptying a bucket at a block.
   *
   * @param bucket the bucket's namespaced item id, such as {@code minecraft:lava_bucket}
   */
  record Bucket(PlayerId player, Position block, String bucket) implements Intent {}

  /**
   * Right-clicking an entity that isn't one of Seasons' displays, such as an item frame.
   *
   * @param type the entity's namespaced type, such as {@code minecraft:item_frame}
   */
  record UseEntity(PlayerId player, Position at, String type) implements Intent {}

  /** Placing an entity: an item frame, a painting, an armour stand, a cushion, a minecart. */
  record PlaceEntity(PlayerId player, Position at, String type) implements Intent {}

  /** Hitting or breaking an entity that isn't a player, by hand or with a projectile. */
  record HurtEntity(PlayerId player, Position at, String type) implements Intent {}

  /**
   * Interacting with one of Seasons' own displays, such as a figure. The adapter always cancels the
   * interaction itself.
   *
   * @param tag the tag the display was spawned with
   * @param sneaking whether the player is sneaking
   */
  record UseDisplay(PlayerId player, String tag, boolean sneaking) implements Intent {
    public UseDisplay(PlayerId player, String tag) {
      this(player, tag, false);
    }
  }

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
    /** A portal. */
    PORTAL,
    /** Seasons itself, such as /hof or launch. */
    SEASONS,
    /** Anything else: commands and other plugins. */
    OTHER
  }

  /** A trip through a portal that would make a new portal at a position, for its other end. */
  record CreatePortal(PlayerId player, Position at) implements Intent {}

  /**
   * Getting on an entity: riding or sitting.
   *
   * @param cushion whether the entity is a cushion
   */
  record Mount(PlayerId player, boolean cushion) implements Intent {}
}
