// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.util.List;
import java.util.Optional;

/**
 * Everything a player carries, as neutral items. Empty slots are empty optionals, so slot positions
 * are kept.
 */
public record InventorySnapshot(
    List<Optional<NeutralItem>> storage,
    List<Optional<NeutralItem>> armour,
    Optional<NeutralItem> offHand,
    List<Optional<NeutralItem>> enderChest) {
  public InventorySnapshot {
    storage = List.copyOf(storage);
    armour = List.copyOf(armour);
    enderChest = List.copyOf(enderChest);
  }
}
