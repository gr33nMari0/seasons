// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.archive;

import java.util.List;
import java.util.Optional;

/**
 * Where one item sits in an archive record, so a curator can move exactly that item: a slot of the
 * inventory, armour (boots first, as the game keeps them), off hand, overflow or ender chest, or
 * for an imported cell a slot of its stand, one of its containers or one of its frames. Its {@link
 * #key} is how audit entries and the journal name it, such as {@code storage.13} or {@code
 * container.1.4}.
 *
 * @param section one of {@link #SECTIONS}
 * @param index the slot, or for a container which container
 * @param slot the container's slot; 0 elsewhere
 */
public record ArchiveSlot(String section, int index, int slot) {
  public static final String STORAGE = "storage";
  public static final String ARMOUR = "armour";
  public static final String OFFHAND = "offhand";
  public static final String OVERFLOW = "overflow";
  public static final String ENDER = "ender";
  public static final String STAND = "stand";
  public static final String CONTAINER = "container";
  public static final String FRAME = "frame";

  public static final List<String> SECTIONS =
      List.of(STORAGE, ARMOUR, OFFHAND, OVERFLOW, ENDER, STAND, CONTAINER, FRAME);

  public ArchiveSlot {
    if (!SECTIONS.contains(section) || index < 0 || slot < 0) {
      throw new IllegalArgumentException("No such archive slot: " + section + " " + index);
    }
  }

  public static ArchiveSlot of(String section, int index) {
    return new ArchiveSlot(section, index, 0);
  }

  /** Such as {@code storage.13}, {@code offhand.0} or {@code container.1.4}. */
  public String key() {
    return section.equals(CONTAINER) ? section + "." + index + "." + slot : section + "." + index;
  }

  /** A slot back from its {@link #key}, if it is one. */
  public static Optional<ArchiveSlot> parse(String key) {
    String[] parts = key.split("\\.", -1);
    try {
      if (parts.length == 3 && parts[0].equals(CONTAINER)) {
        return Optional.of(
            new ArchiveSlot(CONTAINER, Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
      }
      if (parts.length == 2 && !parts[0].equals(CONTAINER)) {
        return Optional.of(of(parts[0], Integer.parseInt(parts[1])));
      }
    } catch (IllegalArgumentException e) {
      // Not a slot.
    }
    return Optional.empty();
  }
}
