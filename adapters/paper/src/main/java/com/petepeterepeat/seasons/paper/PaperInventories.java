// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.port.Inventories;
import com.petepeterepeat.seasons.core.port.InventorySnapshot;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.PlayerId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

final class PaperInventories implements Inventories {
  private final PaperPlayers players;
  private final PaperItemCodec codec;

  PaperInventories(PaperPlayers players, PaperItemCodec codec) {
    this.players = players;
    this.codec = codec;
  }

  @Override
  public InventorySnapshot snapshot(PlayerId id) {
    Player player = online(id);
    PlayerInventory inventory = player.getInventory();
    return new InventorySnapshot(
        encode(inventory.getStorageContents()),
        encode(inventory.getArmorContents()),
        codec.encode(inventory.getItemInOffHand()),
        encode(player.getEnderChest().getContents()));
  }

  @Override
  public void clear(PlayerId id) {
    Player player = online(id);
    player.getInventory().clear();
    player.setItemOnCursor(null);
    // The top inventory is the player's own crafting grid only when no container is open.
    // Otherwise it is a real chest, furnace or the like, which is never cleared.
    Inventory top = player.getOpenInventory().getTopInventory();
    if (top.getType() == InventoryType.CRAFTING) {
      top.clear();
    }
    player.getEnderChest().clear();
  }

  @Override
  public void restore(PlayerId id, InventorySnapshot snapshot) {
    Player player = online(id);
    PlayerInventory inventory = player.getInventory();
    inventory.setStorageContents(decode(snapshot.storage()));
    inventory.setArmorContents(decode(snapshot.armour()));
    inventory.setItemInOffHand(codec.decode(snapshot.offHand()));
    player.getEnderChest().setContents(decode(snapshot.enderChest()));
  }

  private Player online(PlayerId id) {
    return players.player(id).orElseThrow(() -> new IllegalStateException(id + " is offline"));
  }

  private List<Optional<NeutralItem>> encode(
      @org.jspecify.annotations.Nullable ItemStack[] stacks) {
    return Arrays.stream(stacks)
        .map(stack -> stack == null ? Optional.<NeutralItem>empty() : codec.encode(stack))
        .toList();
  }

  private ItemStack[] decode(List<Optional<NeutralItem>> items) {
    return items.stream().map(codec::decode).toArray(ItemStack[]::new);
  }
}
