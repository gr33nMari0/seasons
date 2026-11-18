// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.archive;

import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * What {@code /hof import} took from a hand-built Season 1 cell, with where each item came from, so
 * the archive view shows each container as its own page and {@code /hof import undo} puts every
 * item back exactly where it was.
 *
 * @param stand the armour stand the player's gear stood on, if the cell had one
 * @param containers each chest, barrel or shulker box, in the order their pages show
 * @param frames each item frame, whose item became a trophy
 * @param signs the text of the signs in the cell, for the season card
 */
public record ImportedCell(
    String gallery,
    String cell,
    Optional<Stand> stand,
    List<Container> containers,
    List<Frame> frames,
    List<String> signs) {
  public ImportedCell {
    containers = List.copyOf(containers);
    frames = List.copyOf(frames);
    signs = List.copyOf(signs);
  }

  /** Slots of {@link Stand#equipment}, in order. */
  public static final List<String> EQUIPMENT =
      List.of("head", "chest", "legs", "feet", "mainhand", "offhand");

  /**
   * The armour stand the gear stood on.
   *
   * @param equipment head, chest, legs, feet, main hand, off hand
   * @param properties how to stand it back up: pose, arms, size, name; adapter-defined NBT values
   */
  public record Stand(
      Position at, List<Optional<NeutralItem>> equipment, Map<String, Object> properties) {
    public Stand {
      equipment = List.copyOf(equipment);
      properties = Map.copyOf(properties);
    }
  }

  /**
   * One container, all its slots.
   *
   * @param block the container's block; for a double chest, the half its items start in
   * @param type the block id, such as {@code minecraft:chest}
   * @param slots every slot, empty ones included, so each item keeps its place
   */
  public record Container(Position block, String type, List<Optional<NeutralItem>> slots) {
    public Container {
      slots = List.copyOf(slots);
    }
  }

  /**
   * One item frame.
   *
   * @param properties how to hang it back: facing, rotation, glowing; adapter-defined NBT values
   */
  public record Frame(Position at, Optional<NeutralItem> item, Map<String, Object> properties) {
    public Frame {
      properties = Map.copyOf(properties);
    }
  }

  /** Every stack the import took. */
  public List<NeutralItem> items() {
    return Stream.of(
            stand.stream().flatMap(s -> s.equipment().stream()),
            containers.stream().flatMap(c -> c.slots().stream()),
            frames.stream().map(Frame::item))
        .flatMap(s -> s)
        .flatMap(Optional::stream)
        .toList();
  }
}
