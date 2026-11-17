// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import com.petepeterepeat.seasons.core.port.InventorySnapshot;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.store.nbt.Nbt;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Items for core tests: real NBT, as the adapter would write it, without a server. */
public final class FakeItems {
  public static final int DATA_VERSION = 4500;
  public static final InventorySnapshot EMPTY =
      new InventorySnapshot(empty(36), empty(4), Optional.empty(), empty(27));

  private FakeItems() {}

  public static NeutralItem item(String id, int count) {
    Map<String, Object> nbt = new LinkedHashMap<>();
    nbt.put("id", id);
    nbt.put("count", count);
    nbt.put("DataVersion", DATA_VERSION);
    return new NeutralItem(Nbt.writeCompressed(nbt));
  }

  public static List<Optional<NeutralItem>> empty(int size) {
    return new ArrayList<>(Collections.nCopies(size, Optional.<NeutralItem>empty()));
  }

  /** A sword and dirt in the hotbar, a helmet, a shield, and emeralds in the ender chest. */
  public static InventorySnapshot gear() {
    List<Optional<NeutralItem>> storage = empty(36);
    storage.set(0, Optional.of(item("minecraft:diamond_sword", 1)));
    storage.set(1, Optional.of(item("minecraft:dirt", 64)));
    storage.set(13, Optional.of(item("minecraft:torch", 32)));
    List<Optional<NeutralItem>> armour = empty(4);
    armour.set(3, Optional.of(item("minecraft:iron_helmet", 1)));
    List<Optional<NeutralItem>> ender = empty(27);
    ender.set(0, Optional.of(item("minecraft:emerald", 5)));
    return new InventorySnapshot(storage, armour, Optional.of(item("minecraft:shield", 1)), ender);
  }

  /** Just stone in the first slot, such as what a player gathers in a new season. */
  public static InventorySnapshot stone(int count) {
    List<Optional<NeutralItem>> storage = empty(36);
    storage.set(0, Optional.of(item("minecraft:stone", count)));
    return new InventorySnapshot(storage, empty(4), Optional.empty(), empty(27));
  }
}
