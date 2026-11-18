// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.event.IntentHandler;
import com.petepeterepeat.seasons.core.port.Events;
import com.petepeterepeat.seasons.core.port.Milestones;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Sessions;
import com.petepeterepeat.seasons.core.port.TripwireReport;
import java.util.List;
import java.util.Optional;
import org.bukkit.block.Block;
import org.bukkit.entity.Cushion;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Turns Paper events into core intents and applies the core's decisions; reports joins and leaves;
 * and is the event half of the tripwire.
 */
final class PaperEvents implements Events, Listener {
  private static final String ON_THE_GROUND = "on the ground";
  private static final String IN_AN_INVENTORY = "in an inventory";

  private final Plugin plugin;
  private final PaperText text;
  private final PaperInventories inventories;
  private final PaperPlayers players;
  private final PaperDisplays displays;
  private final PaperAdvancements advancements;
  private @Nullable Milestones milestones;
  private IntentHandler handler = intent -> Decision.allow();
  private @Nullable Sessions sessions;
  private @Nullable TripwireReport tripwire;

  PaperEvents(
      Plugin plugin,
      PaperText text,
      PaperInventories inventories,
      PaperPlayers players,
      PaperDisplays displays,
      PaperAdvancements advancements) {
    this.plugin = plugin;
    this.advancements = advancements;
    this.text = text;
    this.inventories = inventories;
    this.players = players;
    this.displays = displays;
  }

  @Override
  public void handle(IntentHandler handler) {
    this.handler = handler;
  }

  @Override
  public void sessions(Sessions sessions) {
    this.sessions = sessions;
  }

  @Override
  public void tripwire(TripwireReport report) {
    this.tripwire = report;
  }

  @Override
  public void milestones(Milestones listener) {
    this.milestones = listener;
    advancements.milestones(listener);
  }

  /** Arriving in the Nether or the End can be a season first. */
  @EventHandler(priority = EventPriority.MONITOR)
  void onChangedWorld(PlayerChangedWorldEvent event) {
    Milestones listening = milestones;
    if (listening != null) {
      Player player = event.getPlayer();
      listening.enteredDimension(id(player), player.getWorld().getKey().asString());
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  void onJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    boolean playedBefore = player.hasPlayedBefore();
    Sessions joining = sessions;
    if (joining != null) {
      // Held from now, so nothing they carry moves before catch-up has looked at it.
      joining.arriving(id(player));
    }
    // A tick later, once the join has finished, so catch-up may move and clear them.
    player
        .getScheduler()
        .run(
            plugin,
            task -> {
              Sessions listening = sessions;
              if (listening != null && player.isOnline()) {
                listening.joined(PaperPlayers.ref(player), playedBefore);
              }
            },
            null);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  void onQuit(PlayerQuitEvent event) {
    Sessions listening = sessions;
    if (listening != null) {
      listening.left(id(event.getPlayer()));
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onBreak(BlockBreakEvent event) {
    Player player = event.getPlayer();
    apply(
        event,
        player,
        new Intent.BreakBlock(
            id(player), PaperPositions.toPosition(event.getBlock().getLocation())));
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onPlace(BlockPlaceEvent event) {
    Player player = event.getPlayer();
    apply(
        event,
        player,
        new Intent.PlaceBlock(
            id(player),
            PaperPositions.toPosition(event.getBlockPlaced().getLocation()),
            event.getBlockPlaced().getType().getKey().asString()));
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onClick(InventoryClickEvent event) {
    if (event.getWhoClicked() instanceof Player player) {
      apply(event, player, new Intent.UseInventory(id(player)));
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onDrag(InventoryDragEvent event) {
    if (event.getWhoClicked() instanceof Player player) {
      apply(event, player, new Intent.UseInventory(id(player)));
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onDrop(PlayerDropItemEvent event) {
    apply(event, event.getPlayer(), new Intent.DropItem(id(event.getPlayer())));
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onPickup(EntityPickupItemEvent event) {
    if (!(event.getEntity() instanceof Player player)) {
      return;
    }
    if (inventories.isArchived(event.getItem().getItemStack())) {
      event.setCancelled(true);
      String found = PaperInventories.describe(event.getItem().getItemStack());
      event.getItem().remove();
      report(Optional.of(id(player)), ON_THE_GROUND, List.of(found));
      return;
    }
    apply(event, player, new Intent.PickupItem(id(player)));
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onSwap(PlayerSwapHandItemsEvent event) {
    apply(event, event.getPlayer(), new Intent.SwapHands(id(event.getPlayer())));
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onConsume(PlayerItemConsumeEvent event) {
    apply(
        event,
        event.getPlayer(),
        new Intent.Consume(id(event.getPlayer()), event.getItem().getType().getKey().asString()));
  }

  @EventHandler(priority = EventPriority.LOW)
  void onInteract(PlayerInteractEvent event) {
    Player player = event.getPlayer();
    Block clicked = event.getClickedBlock();
    ItemStack item = event.getItem();
    if (event.getAction().isRightClick() && item != null && !item.isEmpty()) {
      // Using the item itself, such as throwing an ender pearl.
      if (handler.decide(new Intent.UseItem(id(player), item.getType().getKey().asString()))
          instanceof Decision.Deny deny) {
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        deny.message().ifPresent(message -> player.sendMessage(text.render(message)));
        return;
      }
    }
    Intent intent =
        event.getAction() == Action.RIGHT_CLICK_BLOCK && clicked != null
            ? new Intent.UseBlock(
                id(player),
                PaperPositions.toPosition(clicked.getLocation()),
                event.getHand() == EquipmentSlot.HAND,
                player.isSneaking(),
                player.getInventory().getItemInMainHand().isEmpty(),
                PaperWorld.isBed(clicked.getType()))
            : new Intent.Interact(id(player));
    if (handler.decide(intent) instanceof Decision.Deny deny) {
      event.setUseInteractedBlock(Event.Result.DENY);
      event.setUseItemInHand(Event.Result.DENY);
      deny.message().ifPresent(message -> player.sendMessage(text.render(message)));
    }
  }

  /** Every interaction with one of Seasons' displays is cancelled; a figure opens its archive. */
  @EventHandler(priority = EventPriority.LOW)
  void onInteractEntity(PlayerInteractEntityEvent event) {
    Player player = event.getPlayer();
    Optional<String> tag = displays.tagOf(event.getRightClicked());
    if (tag.isPresent()) {
      event.setCancelled(true);
      if (event.getHand() == EquipmentSlot.HAND
          && handler.decide(new Intent.UseDisplay(id(player), tag.get()))
              instanceof Decision.Deny deny) {
        deny.message().ifPresent(message -> player.sendMessage(text.render(message)));
      }
      return;
    }
    if (!event.isCancelled()) {
      apply(event, player, new Intent.Interact(id(player)));
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onDamage(EntityDamageEvent event) {
    if (displays.tagOf(event.getEntity()).isPresent()) {
      event.setCancelled(true);
      return;
    }
    if (event.getEntity() instanceof Player player) {
      apply(event, player, new Intent.TakeDamage(id(player)));
    }
  }

  /** Only when the block changes, so the Hall can tell crossing its edge from standing about. */
  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onMove(PlayerMoveEvent event) {
    if (!event.hasChangedBlock()) {
      return;
    }
    Player player = event.getPlayer();
    apply(
        event,
        player,
        new Intent.Move(
            id(player),
            PaperPositions.toPosition(event.getFrom()),
            PaperPositions.toPosition(event.getTo())));
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onTeleport(PlayerTeleportEvent event) {
    Player player = event.getPlayer();
    Intent.TeleportKind kind =
        players.isSeasonsTeleport(player)
            ? Intent.TeleportKind.SEASONS
            : switch (event.getCause()) {
              case ENDER_PEARL -> Intent.TeleportKind.PEARL;
              case CONSUMABLE_EFFECT -> Intent.TeleportKind.CHORUS;
              default -> Intent.TeleportKind.OTHER;
            };
    apply(
        event,
        player,
        new Intent.Teleport(
            id(player),
            PaperPositions.toPosition(event.getFrom()),
            PaperPositions.toPosition(event.getTo()),
            kind));
  }

  /** Riding or sitting: in the Hall, only a cushion is a seat. */
  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onMount(EntityMountEvent event) {
    if (event.getEntity() instanceof Player player) {
      apply(event, player, new Intent.Mount(id(player), event.getMount() instanceof Cushion));
    }
  }

  /** Launch fireworks celebrate; they never hurt anyone, player or not. */
  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onFireworkDamage(EntityDamageByEntityEvent event) {
    if (players.isCelebration(event.getDamager())) {
      event.setCancelled(true);
    }
  }

  /** An archived copy in the world is removed a tick later, so whatever spawned it still works. */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  void onItemSpawn(ItemSpawnEvent event) {
    Item item = event.getEntity();
    if (!inventories.isArchived(item.getItemStack())) {
      return;
    }
    String found = PaperInventories.describe(item.getItemStack());
    item.getScheduler().run(plugin, task -> item.remove(), null);
    report(Optional.empty(), ON_THE_GROUND, List.of(found));
  }

  @EventHandler(priority = EventPriority.MONITOR)
  void onClose(InventoryCloseEvent event) {
    HumanEntity who = event.getPlayer();
    if (who instanceof Player player) {
      // After the close has settled, so the cursor and grid have gone back to the inventory.
      player
          .getScheduler()
          .run(
              plugin,
              task -> {
                List<String> removed = inventories.purgeArchived(id(player));
                if (!removed.isEmpty()) {
                  report(Optional.of(id(player)), IN_AN_INVENTORY, removed);
                }
              },
              null);
    }
  }

  private void report(Optional<PlayerId> player, String where, List<String> items) {
    TripwireReport listening = tripwire;
    if (listening != null) {
      listening.found(player, where, items);
    }
  }

  private void apply(Cancellable event, Player player, Intent intent) {
    switch (handler.decide(intent)) {
      case Decision.Allow allow -> {}
      case Decision.Deny deny -> {
        event.setCancelled(true);
        deny.message().ifPresent(message -> player.sendMessage(text.render(message)));
      }
      case Decision.Redirect redirect -> {
        event.setCancelled(true);
        var _ = player.teleportAsync(PaperPositions.toLocation(redirect.to()));
        redirect.message().ifPresent(message -> player.sendMessage(text.render(message)));
      }
    }
  }

  private static PlayerId id(Player player) {
    return new PlayerId(player.getUniqueId());
  }
}
