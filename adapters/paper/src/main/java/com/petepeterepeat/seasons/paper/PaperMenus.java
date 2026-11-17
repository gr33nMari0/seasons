// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.port.Menus;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.PlayerId;
import java.util.List;
import java.util.Optional;
import org.bukkit.Server;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** View-only menus: every click and drag is refused, then the client is resynced. */
final class PaperMenus implements Menus, Listener {
  private static final int ROW = 9;
  private static final int MAX_SLOTS = 6 * ROW;

  private final Server server;
  private final PaperPlayers players;
  private final PaperItemCodec codec;
  private final PaperText text;

  PaperMenus(Server server, PaperPlayers players, PaperItemCodec codec, PaperText text) {
    this.server = server;
    this.players = players;
    this.codec = codec;
    this.text = text;
  }

  @Override
  public void openViewOnly(PlayerId viewer, Message title, List<Optional<NeutralItem>> items) {
    Player player =
        players.player(viewer).orElseThrow(() -> new IllegalStateException(viewer + " is offline"));
    int slots = Math.min(MAX_SLOTS, Math.max(ROW, (items.size() + ROW - 1) / ROW * ROW));
    ViewOnlyHolder holder = new ViewOnlyHolder();
    Inventory inventory = server.createInventory(holder, slots, text.render(title));
    holder.inventory = inventory;
    for (int i = 0; i < Math.min(slots, items.size()); i++) {
      // A throwaway copy decoded from the stored bytes: never the archived item itself.
      ItemStack copy = codec.decode(items.get(i));
      inventory.setItem(i, copy);
    }
    player.openInventory(inventory);
  }

  @EventHandler(priority = EventPriority.LOWEST)
  void onClick(InventoryClickEvent event) {
    if (event.getView().getTopInventory().getHolder(false) instanceof ViewOnlyHolder) {
      event.setCancelled(true);
      resync(event.getWhoClicked());
    }
  }

  @EventHandler(priority = EventPriority.LOWEST)
  void onDrag(InventoryDragEvent event) {
    if (event.getView().getTopInventory().getHolder(false) instanceof ViewOnlyHolder) {
      event.setCancelled(true);
      resync(event.getWhoClicked());
    }
  }

  @EventHandler(priority = EventPriority.LOWEST)
  void onMove(InventoryMoveItemEvent event) {
    if (event.getSource().getHolder(false) instanceof ViewOnlyHolder
        || event.getDestination().getHolder(false) instanceof ViewOnlyHolder) {
      event.setCancelled(true);
    }
  }

  private static void resync(HumanEntity who) {
    if (who instanceof Player player) {
      player.updateInventory();
    }
  }

  /** Marks a Seasons view-only menu. */
  static final class ViewOnlyHolder implements InventoryHolder {
    private @org.jspecify.annotations.Nullable Inventory inventory;

    @Override
    public Inventory getInventory() {
      if (inventory == null) {
        throw new IllegalStateException("Menu not built yet");
      }
      return inventory;
    }
  }
}
