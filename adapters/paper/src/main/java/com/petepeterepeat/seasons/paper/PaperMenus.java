// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.port.Menus;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.PagedView;
import com.petepeterepeat.seasons.core.port.PlayerId;
import java.util.List;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * View-only, withdraw-only and choice menus: every click and drag is refused, then the client is
 * resynced; in a withdraw-only menu a left or shift click on an item then asks the core to withdraw
 * it, and in a choice menu a left click on a choice tells the core which. Every item shown is a
 * throwaway copy tagged {@code seasons:archived}, which the tripwire deletes wherever else it turns
 * up.
 */
final class PaperMenus implements Menus, Listener {
  private static final int ROW = 9;
  private static final int MAX_SLOTS = 6 * ROW;
  static final int PREVIOUS = 45;
  static final int LABEL = 49;
  static final int NEXT = 53;

  private final Server server;
  private final PaperPlayers players;
  private final PaperItemCodec codec;
  private final PaperText text;
  private final NamespacedKey archivedKey;

  PaperMenus(
      Server server,
      PaperPlayers players,
      PaperItemCodec codec,
      PaperText text,
      NamespacedKey archivedKey) {
    this.server = server;
    this.players = players;
    this.codec = codec;
    this.text = text;
    this.archivedKey = archivedKey;
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
      inventory.setItem(i, copy(items.get(i)));
    }
    player.openInventory(inventory);
  }

  @Override
  public void openPaged(PlayerId viewer, PagedView view, int startPage) {
    Player player =
        players.player(viewer).orElseThrow(() -> new IllegalStateException(viewer + " is offline"));
    PagedHolder holder = new PagedHolder(view);
    Inventory inventory = server.createInventory(holder, MAX_SLOTS, text.render(view.title()));
    holder.inventory = inventory;
    show(holder, Math.max(0, Math.min(startPage, view.pages().size() - 1)));
    player.openInventory(inventory);
  }

  @Override
  public void openWithdraw(PlayerId viewer, PagedView view, int startPage, Withdrawal taken) {
    Player player =
        players.player(viewer).orElseThrow(() -> new IllegalStateException(viewer + " is offline"));
    PagedHolder holder = new PagedHolder(view);
    holder.taken = taken;
    Inventory inventory = server.createInventory(holder, MAX_SLOTS, text.render(view.title()));
    holder.inventory = inventory;
    show(holder, Math.max(0, Math.min(startPage, view.pages().size() - 1)));
    player.openInventory(inventory);
  }

  @Override
  public void close(PlayerId viewer) {
    players.player(viewer).ifPresent(Player::closeInventory);
  }

  @Override
  public void closeOwn(PlayerId viewer) {
    players
        .player(viewer)
        .filter(player -> isViewOnly(player.getOpenInventory().getTopInventory()))
        .ifPresent(Player::closeInventory);
  }

  @Override
  public void openChoice(PlayerId viewer, Message title, List<Choice> choices, Chosen chosen) {
    Player player =
        players.player(viewer).orElseThrow(() -> new IllegalStateException(viewer + " is offline"));
    int slots = Math.min(MAX_SLOTS, Math.max(ROW, (choices.size() + ROW - 1) / ROW * ROW));
    ChoiceHolder holder = new ChoiceHolder(choices.size(), chosen);
    Inventory inventory = server.createInventory(holder, slots, text.render(title));
    holder.inventory = inventory;
    for (int i = 0; i < Math.min(slots, choices.size()); i++) {
      Choice choice = choices.get(i);
      Material icon = Material.matchMaterial(choice.icon());
      inventory.setItem(
          i, button(icon == null || !icon.isItem() ? Material.PAPER : icon, choice.label()));
    }
    player.openInventory(inventory);
  }

  @EventHandler(priority = EventPriority.LOWEST)
  void onClick(InventoryClickEvent event) {
    if (!isViewOnly(event.getView().getTopInventory())) {
      return;
    }
    // Refused first, whatever the click: the buttons only turn the page.
    event.setCancelled(true);
    if (event.getView().getTopInventory().getHolder(false) instanceof ChoiceHolder choice) {
      int raw = event.getRawSlot();
      resync(event.getWhoClicked());
      if (event.getClick() == ClickType.LEFT && raw >= 0 && raw < choice.count) {
        // Asked only after the refusal, as a withdrawal is.
        choice.chosen.chosen(raw);
      }
      return;
    }
    if (event.getView().getTopInventory().getHolder(false) instanceof PagedHolder paged
        && event.getClick() != ClickType.CREATIVE) {
      int raw = event.getRawSlot();
      if (raw == PREVIOUS && paged.page > 0) {
        show(paged, paged.page - 1);
      } else if (raw == NEXT && paged.page < paged.view.pages().size() - 1) {
        show(paged, paged.page + 1);
      } else if (paged.taken != null
          && raw >= 0
          && raw < PagedView.PAGE_SIZE
          && (event.getClick() == ClickType.LEFT || event.getClick() == ClickType.SHIFT_LEFT)
          && shows(paged, raw)) {
        // Asked only after the refusal: the core withdraws from its own record, never this menu.
        resync(event.getWhoClicked());
        paged.taken.clicked(paged.page, raw);
        return;
      }
    }
    resync(event.getWhoClicked());
  }

  @EventHandler(priority = EventPriority.LOWEST)
  void onCreative(InventoryCreativeEvent event) {
    if (isViewOnly(event.getView().getTopInventory())) {
      event.setCancelled(true);
      resync(event.getWhoClicked());
    }
  }

  @EventHandler(priority = EventPriority.LOWEST)
  void onDrag(InventoryDragEvent event) {
    if (isViewOnly(event.getView().getTopInventory())) {
      event.setCancelled(true);
      resync(event.getWhoClicked());
    }
  }

  @EventHandler(priority = EventPriority.LOWEST)
  void onMove(InventoryMoveItemEvent event) {
    if (isViewOnly(event.getSource()) || isViewOnly(event.getDestination())) {
      event.setCancelled(true);
    }
  }

  private static boolean shows(PagedHolder holder, int slot) {
    ItemStack shown = holder.getInventory().getItem(slot);
    return shown != null && !shown.isEmpty();
  }

  static boolean isViewOnly(Inventory inventory) {
    InventoryHolder holder = inventory.getHolder(false);
    return holder instanceof ViewOnlyHolder
        || holder instanceof PagedHolder
        || holder instanceof ChoiceHolder;
  }

  private void show(PagedHolder holder, int page) {
    Inventory inventory = holder.getInventory();
    holder.page = page;
    inventory.clear();
    List<Optional<NeutralItem>> items = holder.view.pages().get(page);
    for (int i = 0; i < items.size(); i++) {
      inventory.setItem(i, copy(items.get(i)));
    }
    if (page > 0) {
      inventory.setItem(PREVIOUS, button(Material.ARROW, holder.view.previous()));
    }
    inventory.setItem(LABEL, button(Material.PAPER, holder.view.pageLabels().get(page)));
    if (page < holder.view.pages().size() - 1) {
      inventory.setItem(NEXT, button(Material.ARROW, holder.view.next()));
    }
  }

  /** A throwaway copy decoded from the stored bytes, never the archived item itself, tagged. */
  private ItemStack copy(Optional<NeutralItem> item) {
    ItemStack copy = codec.decode(item);
    if (!copy.isEmpty()) {
      copy.editPersistentDataContainer(
          data -> data.set(archivedKey, PersistentDataType.BOOLEAN, true));
    }
    return copy;
  }

  private ItemStack button(Material material, Message label) {
    ItemStack button = ItemStack.of(material);
    button.editMeta(meta -> meta.customName(text.render(label)));
    button.editPersistentDataContainer(
        data -> data.set(archivedKey, PersistentDataType.BOOLEAN, true));
    return button;
  }

  private static void resync(HumanEntity who) {
    if (who instanceof Player player) {
      player.updateInventory();
    }
  }

  /** Marks a paged Seasons view, and remembers which page is showing. */
  static final class PagedHolder implements InventoryHolder {
    private final PagedView view;
    private int page;

    /** Who hears about clicks on items, in a withdraw-only menu; null in a view-only one. */
    private @org.jspecify.annotations.Nullable Withdrawal taken;

    private @org.jspecify.annotations.Nullable Inventory inventory;

    PagedHolder(PagedView view) {
      this.view = view;
    }

    @Override
    public Inventory getInventory() {
      if (inventory == null) {
        throw new IllegalStateException("Menu not built yet");
      }
      return inventory;
    }
  }

  /** Marks a menu of choices, and who hears which is clicked. */
  static final class ChoiceHolder implements InventoryHolder {
    private final int count;
    private final Chosen chosen;
    private @org.jspecify.annotations.Nullable Inventory inventory;

    ChoiceHolder(int count, Chosen chosen) {
      this.count = count;
      this.chosen = chosen;
    }

    @Override
    public Inventory getInventory() {
      if (inventory == null) {
        throw new IllegalStateException("Menu not built yet");
      }
      return inventory;
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
