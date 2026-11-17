// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * What {@code /hof import} needs from the world: what a hand-built cell holds, and taking it out or
 * putting it back. Only on the server thread.
 */
public interface Imports {
  /** Loads the chunks between two corners and reports what stands there. */
  CompletableFuture<Scan> scan(Position min, Position max);

  /** Empties a container. False if the block isn't a container. */
  boolean emptyContainer(Position block);

  /**
   * Fills an empty container's slots. False, and nothing changes, if it isn't a container, holds
   * anything, or has a different number of slots.
   */
  boolean fillContainer(Position block, List<Optional<NeutralItem>> slots);

  /** Removes an armour stand or item frame without dropping anything. False if it is gone. */
  boolean remove(UUID entity);

  /** Stands an armour stand back up, wearing the equipment (head to off hand). */
  UUID spawnStand(Position at, Map<String, Object> properties, List<Optional<NeutralItem>> worn);

  /** Hangs an item frame back up, holding the item. */
  UUID spawnFrame(Position at, Map<String, Object> properties, Optional<NeutralItem> item);

  /**
   * Everything in a box that an import cares about.
   *
   * @param signs the text of each sign, one string per sign
   * @param others other containers holding items, which the import leaves alone
   */
  record Scan(
      List<Container> containers,
      List<Stand> stands,
      List<Frame> frames,
      List<String> signs,
      List<Other> others) {
    public Scan {
      containers = List.copyOf(containers);
      stands = List.copyOf(stands);
      frames = List.copyOf(frames);
      signs = List.copyOf(signs);
      others = List.copyOf(others);
    }
  }

  /**
   * A chest, barrel or shulker box; a double chest is one, at the half its slots start in.
   *
   * @param slots every slot, empty ones included
   */
  record Container(Position block, String type, List<Optional<NeutralItem>> slots) {
    public Container {
      slots = List.copyOf(slots);
    }
  }

  /**
   * An armour stand.
   *
   * @param equipment head, chest, legs, feet, main hand, off hand
   * @param heads the player heads among its equipment
   * @param properties what it takes to stand it back up, as NBT values
   */
  record Stand(
      UUID id,
      Position at,
      List<Optional<NeutralItem>> equipment,
      List<Head> heads,
      Map<String, Object> properties) {
    public Stand {
      equipment = List.copyOf(equipment);
      heads = List.copyOf(heads);
      properties = Map.copyOf(properties);
    }
  }

  /** A player head's profile: whose it is, and the skin it shows. */
  record Head(Optional<PlayerId> owner, Optional<String> name, Optional<Skin> skin) {}

  /** An item frame, glowing or not. */
  record Frame(UUID id, Position at, Optional<NeutralItem> item, Map<String, Object> properties) {
    public Frame {
      properties = Map.copyOf(properties);
    }
  }

  /** Another container with items in it, such as a furnace. */
  record Other(Position block, String type, int items) {}
}
