// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.port.Inventories;
import com.petepeterepeat.seasons.core.port.InventorySnapshot;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.store.nbt.Nbt;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jspecify.annotations.Nullable;

final class PaperInventories implements Inventories {
  private final PaperPlayers players;
  private final PaperItemCodec codec;
  private final NamespacedKey archivedKey;
  private int dataVersion;

  PaperInventories(PaperPlayers players, PaperItemCodec codec, NamespacedKey archivedKey) {
    this.players = players;
    this.codec = codec;
    this.archivedKey = archivedKey;
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

  /** The cursor and crafting grid are emptied, and what was there is returned as overflow. */
  @Override
  public List<NeutralItem> settle(PlayerId id) {
    Player player = online(id);
    List<NeutralItem> overflow = new ArrayList<>();
    codec.encode(player.getItemOnCursor()).ifPresent(overflow::add);
    player.setItemOnCursor(null);
    // The player's own 2x2 grid, or a crafting table's grid while one is open: both hold items
    // the player put there. Other containers belong to the world and are never touched.
    if (player.getOpenInventory().getTopInventory() instanceof CraftingInventory crafting) {
      for (ItemStack stack : crafting.getMatrix()) {
        if (stack != null) {
          codec.encode(stack).ifPresent(overflow::add);
        }
      }
      crafting.setMatrix(new ItemStack[crafting.getMatrix().length]);
    }
    return overflow;
  }

  @Override
  public void clear(PlayerId id, boolean enderChest) {
    Player player = online(id);
    player.getInventory().clear();
    player.setItemOnCursor(null);
    // The top inventory is the player's own crafting grid only when no container is open.
    // Otherwise it is a real chest, furnace or the like, which is never cleared.
    Inventory top = player.getOpenInventory().getTopInventory();
    if (top.getType() == InventoryType.CRAFTING) {
      top.clear();
    }
    if (enderChest) {
      player.getEnderChest().clear();
    }
  }

  @Override
  public void restore(PlayerId id, InventorySnapshot snapshot) {
    Player player = online(id);
    PlayerInventory inventory = player.getInventory();
    inventory.setStorageContents(decode(snapshot.storage(), inventory.getStorageContents().length));
    inventory.setArmorContents(decode(snapshot.armour(), inventory.getArmorContents().length));
    inventory.setItemInOffHand(codec.decode(snapshot.offHand()));
    Inventory ender = player.getEnderChest();
    ender.setContents(decode(snapshot.enderChest(), ender.getSize()));
  }

  @Override
  public List<String> purgeArchived(PlayerId id) {
    Optional<Player> found = players.player(id);
    if (found.isEmpty()) {
      return List.of();
    }
    Player player = found.get();
    List<String> removed = new ArrayList<>();
    purge(player.getInventory(), removed);
    purge(player.getEnderChest(), removed);
    if (isArchived(player.getItemOnCursor())) {
      removed.add(describe(player.getItemOnCursor()));
      player.setItemOnCursor(null);
    }
    Inventory top = player.getOpenInventory().getTopInventory();
    if (!(top.getHolder(false) instanceof PaperMenus.PagedHolder)
        && !(top.getHolder(false) instanceof PaperMenus.ViewOnlyHolder)) {
      purge(top, removed);
    }
    return removed;
  }

  @Override
  public int dataVersion() {
    if (dataVersion == 0) {
      byte[] probe = codec.encode(ItemStack.of(Material.STONE)).orElseThrow().nbt();
      try {
        dataVersion = Nbt.read(probe).get("DataVersion") instanceof Integer version ? version : -1;
      } catch (IOException e) {
        dataVersion = -1;
      }
    }
    return dataVersion;
  }

  /** Whether a stack carries the tag every archive-view copy has. */
  boolean isArchived(@Nullable ItemStack stack) {
    return stack != null && !stack.isEmpty() && stack.getPersistentDataContainer().has(archivedKey);
  }

  /** Such as {@code 3 × minecraft:stone}, for the audit log. */
  static String describe(ItemStack stack) {
    return stack.getAmount() + " × " + stack.getType().getKey().asString();
  }

  private void purge(Inventory inventory, List<String> removed) {
    @Nullable ItemStack[] contents = inventory.getContents();
    for (int slot = 0; slot < contents.length; slot++) {
      ItemStack stack = contents[slot];
      if (isArchived(stack)) {
        removed.add(describe(stack));
        inventory.setItem(slot, null);
      }
    }
  }

  private Player online(PlayerId id) {
    return players.player(id).orElseThrow(() -> new IllegalStateException(id + " is offline"));
  }

  private List<Optional<NeutralItem>> encode(@Nullable ItemStack[] stacks) {
    return Arrays.stream(stacks)
        .map(stack -> stack == null ? Optional.<NeutralItem>empty() : codec.encode(stack))
        .toList();
  }

  /** Items into exactly {@code size} slots, so a short or long list never shifts or throws. */
  private ItemStack[] decode(List<Optional<NeutralItem>> items, int size) {
    ItemStack[] stacks = new ItemStack[size];
    for (int i = 0; i < size; i++) {
      stacks[i] = i < items.size() ? codec.decode(items.get(i)) : ItemStack.empty();
    }
    return stacks;
  }
}
