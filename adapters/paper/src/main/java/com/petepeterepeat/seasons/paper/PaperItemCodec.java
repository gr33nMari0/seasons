// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.port.NeutralItem;
import java.util.Optional;
import org.bukkit.inventory.ItemStack;

/**
 * Converts item stacks to neutral items and back. {@link ItemStack#serializeAsBytes()} writes
 * vanilla item data with its {@code DataVersion}, and Paper upgrades older data when it loads.
 */
public final class PaperItemCodec {
  public Optional<NeutralItem> encode(ItemStack stack) {
    if (stack.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new NeutralItem(stack.serializeAsBytes()));
  }

  public ItemStack decode(NeutralItem item) {
    return ItemStack.deserializeBytes(item.nbt());
  }

  public ItemStack decode(Optional<NeutralItem> item) {
    return item.map(this::decode).orElseGet(ItemStack::empty);
  }
}
