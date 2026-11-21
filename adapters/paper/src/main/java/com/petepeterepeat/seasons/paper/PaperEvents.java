// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.event.IntentHandler;
import com.petepeterepeat.seasons.core.port.ChatInput;
import com.petepeterepeat.seasons.core.port.DragonEvents;
import com.petepeterepeat.seasons.core.port.Events;
import com.petepeterepeat.seasons.core.port.HallGuard;
import com.petepeterepeat.seasons.core.port.Milestones;
import com.petepeterepeat.seasons.core.port.PetEvents;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.Sessions;
import com.petepeterepeat.seasons.core.port.TripwireReport;
import com.petepeterepeat.seasons.core.port.ZoneGuard;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Cake;
import org.bukkit.block.data.type.Comparator;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.block.data.type.Repeater;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Cushion;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Vehicle;
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
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
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
  private static final String IN_A_CONTAINER = "in a container";
  private static final String IN_A_HALL_CONTAINER = "in a Hall container";
  private static final String IN_AN_ITEM_FRAME = "in an item frame";
  private static final String ON_AN_ARMOUR_STAND = "on an armour stand";
  private static final String ON_AN_ENTITY = "on an entity";
  private static final String IN_AN_ENTITY = "in an entity's inventory";

  private final Plugin plugin;
  private final PaperText text;
  private final PaperInventories inventories;
  private final PaperPlayers players;
  private final PaperDisplays displays;
  private final PaperAdvancements advancements;
  private final PaperEntities entities;
  private final PaperHallGuard hallGuard;
  private final PaperZoneGuard zoneGuard;
  private final PaperDragonEvents dragonEvents = new PaperDragonEvents();
  private @Nullable Milestones milestones;
  private IntentHandler handler = intent -> Decision.allow();
  private @Nullable Sessions sessions;
  private @Nullable TripwireReport tripwire;
  private volatile ChatInput chat = ChatInput.NONE;

  /** The tick each player's last wand click was Seasons', so its off-hand twin is dropped too. */
  private final java.util.Map<java.util.UUID, Integer> wandTicks = new java.util.HashMap<>();

  PaperEvents(
      Plugin plugin,
      PaperText text,
      PaperInventories inventories,
      PaperPlayers players,
      PaperDisplays displays,
      PaperAdvancements advancements,
      PaperEntities entities) {
    this.plugin = plugin;
    this.entities = entities;
    this.advancements = advancements;
    this.text = text;
    this.inventories = inventories;
    this.players = players;
    this.displays = displays;
    this.hallGuard = new PaperHallGuard(this);
    this.zoneGuard = new PaperZoneGuard(this);
  }

  /** The Hall's protection, a listener of its own. */
  PaperHallGuard hallGuard() {
    return hallGuard;
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
  public void hall(HallGuard guard) {
    hallGuard.guard(guard);
  }

  /** The zones' protection, a listener of its own. */
  PaperZoneGuard zoneGuard() {
    return zoneGuard;
  }

  @Override
  public void zones(ZoneGuard guard) {
    zoneGuard.guard(guard);
  }

  @Override
  public void pets(PetEvents pets) {
    entities.listen(pets);
  }

  /** The End's dragons, a listener of its own. */
  PaperDragonEvents dragonEvents() {
    return dragonEvents;
  }

  @Override
  public void dragon(DragonEvents dragon) {
    dragonEvents.listen(dragon);
  }

  @Override
  public void chat(ChatInput input) {
    this.chat = input;
  }

  /**
   * A line Seasons is waiting for, such as a curator's reason, is taken before anyone sees it:
   * cancelled at the lowest priority, so no player, and no chat bridge that skips cancelled chat,
   * ever gets it. Paper runs this off the server thread; the core hands it on.
   */
  @EventHandler(priority = EventPriority.LOWEST)
  void onChat(AsyncChatEvent event) {
    String line = PlainTextComponentSerializer.plainText().serialize(event.message());
    if (chat.typed(id(event.getPlayer()), line)) {
      event.setCancelled(true);
    }
  }

  /** An ender dragon's death can unlock zones or bring Homecoming. */
  @EventHandler(priority = EventPriority.MONITOR)
  void onDeath(EntityDeathEvent event) {
    Milestones listening = milestones;
    if (listening != null && event.getEntity() instanceof EnderDragon) {
      listening.dragonKilled();
    }
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

  /** After a respawn, once the game has sent the client the world there, its border included. */
  @EventHandler(priority = EventPriority.MONITOR)
  void onPostRespawn(PlayerPostRespawnEvent event) {
    Milestones listening = milestones;
    if (listening != null) {
      listening.respawned(id(event.getPlayer()));
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
    wandTicks.remove(event.getPlayer().getUniqueId());
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
    // A chest that joined another: the double chest it makes must stay on one side of every edge.
    List<Block> halves = PaperChests.halves(event.getBlockPlaced());
    if (!event.isCancelled() && halves.size() > 1) {
      apply(event, player, new Intent.JoinContainers(id(player), PaperChests.positions(halves)));
    }
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
    apply(
        event,
        player,
        new Intent.PickupItem(
            id(player), PaperPositions.toPosition(event.getItem().getLocation())));
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
    if (clicked != null && wandClick(event, player, clicked)) {
      return;
    }
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
                PaperWorld.isBed(clicked.getType()),
                isInteractable(clicked))
            : new Intent.Interact(id(player));
    if (handler.decide(intent) instanceof Decision.Deny deny) {
      event.setUseInteractedBlock(Event.Result.DENY);
      event.setUseItemInHand(Event.Result.DENY);
      deny.message().ifPresent(message -> player.sendMessage(text.render(message)));
    }
  }

  /**
   * A click on a block with the Seasons wand in the main hand (questions 226): the core says
   * whether it was Seasons'; if so the block is neither broken, opened nor used, and the off hand's
   * click in the same tick goes no further either. Returns whether the event is dealt with.
   */
  private boolean wandClick(PlayerInteractEvent event, Player player, Block clicked) {
    Action action = event.getAction();
    if ((action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK)
        || !inventories.isWand(player.getInventory().getItemInMainHand())) {
      return false;
    }
    int tick = plugin.getServer().getCurrentTick();
    if (event.getHand() == EquipmentSlot.OFF_HAND) {
      if (wandTicks.getOrDefault(player.getUniqueId(), -1) == tick) {
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        return true;
      }
      return false;
    }
    Decision decision =
        handler.decide(
            new Intent.UseWand(
                id(player),
                PaperPositions.toPosition(clicked.getLocation()),
                face(event.getBlockFace()),
                action == Action.LEFT_CLICK_BLOCK,
                player.isSneaking()));
    if (decision instanceof Decision.Deny deny) {
      event.setUseInteractedBlock(Event.Result.DENY);
      event.setUseItemInHand(Event.Result.DENY);
      deny.message().ifPresent(message -> player.sendMessage(text.render(message)));
      wandTicks.put(player.getUniqueId(), tick);
      return true;
    }
    return false;
  }

  private static String face(BlockFace face) {
    return switch (face) {
      case DOWN -> "down";
      case NORTH -> "north";
      case SOUTH -> "south";
      case EAST -> "east";
      case WEST -> "west";
      default -> "up";
    };
  }

  /** Every interaction with one of Seasons' displays is cancelled; a figure opens its archive. */
  @EventHandler(priority = EventPriority.LOW)
  void onInteractEntity(PlayerInteractEntityEvent event) {
    Player player = event.getPlayer();
    Optional<String> tag = displays.tagOf(event.getRightClicked());
    if (tag.isPresent()) {
      event.setCancelled(true);
      if (event.getHand() == EquipmentSlot.HAND
          && handler.decide(new Intent.UseDisplay(id(player), tag.get(), player.isSneaking()))
              instanceof Decision.Deny deny) {
        deny.message().ifPresent(message -> player.sendMessage(text.render(message)));
      }
      return;
    }
    if (!event.isCancelled()) {
      Entity entity = event.getRightClicked();
      apply(
          event,
          player,
          new Intent.UseEntity(
              id(player),
              PaperPositions.toPosition(entity.getLocation()),
              entity.getType().getKey().asString()));
    }
  }

  /** Right-clicking a point on an entity, as armour stands are used: the same as any click. */
  @EventHandler(priority = EventPriority.LOW)
  void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
    onInteractEntity(event);
  }

  @EventHandler(priority = EventPriority.LOW)
  void onArmorStand(PlayerArmorStandManipulateEvent event) {
    onInteractEntity(event);
  }

  /**
   * Opening a block's inventory, both halves of a double chest: in the Hall, often swapped for a
   * view-only copy.
   */
  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onOpen(InventoryOpenEvent event) {
    List<Block> blocks = PaperChests.blocks(event.getInventory());
    if (event.getPlayer() instanceof Player player && !blocks.isEmpty()) {
      apply(event, player, new Intent.OpenContainer(id(player), PaperChests.positions(blocks)));
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onDamage(EntityDamageEvent event) {
    Entity entity = event.getEntity();
    if (displays.tagOf(entity).isPresent()) {
      event.setCancelled(true);
      return;
    }
    Position at = PaperPositions.toPosition(entity.getLocation());
    // A player's hit on an entity, by hand or with a projectile, is the Hall's rules' or the
    // zones' to decide, wherever it is: from a zone's edge it can knock an item out of a frame or
    // break an armour stand. Minecarts and boats are decided as vehicles (onVehicleDamage).
    Player attacker =
        event instanceof EntityDamageByEntityEvent byEntity
            ? responsible(byEntity.getDamager())
            : null;
    if (attacker != null && !(entity instanceof Player) && !isVehicle(entity)) {
      apply(
          event,
          attacker,
          new Intent.HurtEntity(id(attacker), at, entity.getType().getKey().asString()));
      return;
    }
    if (hallGuard.guard().protects(at)) {
      // Nothing in the Hall is hurt but by /kill and the void.
      if (event.getCause() != EntityDamageEvent.DamageCause.KILL
          && event.getCause() != EntityDamageEvent.DamageCause.VOID) {
        event.setCancelled(true);
        return;
      }
    }
    if (entity instanceof Player player) {
      apply(event, player, new Intent.TakeDamage(id(player)));
    }
  }

  /** A player's hit on a minecart or boat, which can break it and spill what it carries. */
  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onVehicleDamage(VehicleDamageEvent event) {
    Player attacker = responsible(event.getAttacker());
    if (attacker != null) {
      Vehicle vehicle = event.getVehicle();
      apply(
          event,
          attacker,
          new Intent.HurtEntity(
              id(attacker),
              PaperPositions.toPosition(vehicle.getLocation()),
              vehicle.getType().getKey().asString()));
    }
  }

  /** A minecart or boat: damaged through {@link VehicleDamageEvent}, not as a living thing. */
  static boolean isVehicle(Entity entity) {
    return entity instanceof Minecart || entity instanceof Boat;
  }

  /** Reeling in a hooked entity pulls it, or an item, towards the player: using it. */
  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onFish(PlayerFishEvent event) {
    Entity caught = event.getCaught();
    if (event.getState() != PlayerFishEvent.State.CAUGHT_ENTITY || caught == null) {
      return;
    }
    Player player = event.getPlayer();
    apply(
        event,
        player,
        new Intent.UseEntity(
            id(player),
            PaperPositions.toPosition(caught.getLocation()),
            caught.getType().getKey().asString()));
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
              // The game's portal trip, sent to the portal it found at the other end.
              case NETHER_PORTAL, END_PORTAL, END_GATEWAY -> Intent.TeleportKind.PORTAL;
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

  /** A trip through a portal: its destination may be somewhere nobody may go. */
  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onPortal(PlayerPortalEvent event) {
    Player player = event.getPlayer();
    apply(
        event,
        player,
        new Intent.Teleport(
            id(player),
            PaperPositions.toPosition(event.getFrom()),
            PaperPositions.toPosition(event.getTo()),
            Intent.TeleportKind.PORTAL));
  }

  /**
   * A vehicle moving to another block with players in it: each player is moving too. A move refused
   * leaves the vehicle and its players where they came from, the players dismounted.
   */
  @EventHandler(priority = EventPriority.LOW)
  void onVehicleMove(VehicleMoveEvent event) {
    Location from = event.getFrom();
    Location to = event.getTo();
    if (from.getBlockX() == to.getBlockX()
        && from.getBlockY() == to.getBlockY()
        && from.getBlockZ() == to.getBlockZ()) {
      return;
    }
    Vehicle vehicle = event.getVehicle();
    for (Entity passenger : vehicle.getPassengers()) {
      if (passenger instanceof Player player
          && handler.decide(
                  new Intent.Move(
                      id(player), PaperPositions.toPosition(from), PaperPositions.toPosition(to)))
              instanceof Decision.Deny deny) {
        vehicle.eject();
        vehicle.teleport(from);
        player.teleport(from);
        deny.message().ifPresent(message -> player.sendMessage(text.render(message)));
        return;
      }
    }
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

  /**
   * An archived copy moving through a hopper anywhere is stopped and deleted where it came from.
   */
  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  void onMoveItem(InventoryMoveItemEvent event) {
    if (inventories.isArchived(event.getItem())) {
      event.setCancelled(true);
      Inventory source = event.getSource();
      Bukkit.getGlobalRegionScheduler()
          .execute(
              plugin,
              () -> {
                List<String> removed = inventories.purge(source);
                if (!removed.isEmpty()) {
                  report(Optional.empty(), IN_A_CONTAINER, removed);
                }
              });
    }
  }

  /**
   * Entities just loaded: archived copies in item frames, on armour stands or other entities, or
   * lying about, are deleted; and the Hall reconciles its cells there.
   */
  @EventHandler(priority = EventPriority.MONITOR)
  void onEntitiesLoad(EntitiesLoadEvent event) {
    for (Entity entity : event.getEntities()) {
      if (displays.tagOf(entity).isEmpty()) {
        purge(entity);
      }
    }
    Chunk chunk = event.getChunk();
    hallGuard
        .guard()
        .entitiesLoaded(chunk.getWorld().getKey().asString(), chunk.getX(), chunk.getZ());
  }

  private void purge(Entity entity) {
    switch (entity) {
      case Item item when inventories.isArchived(item.getItemStack()) -> {
        String found = PaperInventories.describe(item.getItemStack());
        item.remove();
        report(Optional.empty(), ON_THE_GROUND, List.of(found));
      }
      case ItemFrame frame when inventories.isArchived(frame.getItem()) -> {
        String found = PaperInventories.describe(frame.getItem());
        frame.setItem(null, false);
        report(Optional.empty(), IN_AN_ITEM_FRAME, List.of(found));
      }
      case LivingEntity living when living.getEquipment() != null -> {
        EntityEquipment equipment = living.getEquipment();
        List<String> removed = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
          if (living.canUseEquipmentSlot(slot) && inventories.isArchived(equipment.getItem(slot))) {
            removed.add(PaperInventories.describe(equipment.getItem(slot)));
            equipment.setItem(slot, null);
          }
        }
        if (!removed.isEmpty()) {
          report(
              Optional.empty(),
              entity instanceof ArmorStand ? ON_AN_ARMOUR_STAND : ON_AN_ENTITY,
              removed);
        }
      }
      default -> {}
    }
    if (entity instanceof InventoryHolder holder && !(entity instanceof Player)) {
      List<String> removed = inventories.purge(holder.getInventory());
      if (!removed.isEmpty()) {
        report(Optional.empty(), IN_AN_ENTITY, removed);
      }
    }
  }

  /** A chunk in the Hall just loaded: archived copies in its containers are deleted. */
  @EventHandler(priority = EventPriority.MONITOR)
  void onChunkLoad(ChunkLoadEvent event) {
    HallGuard guard = hallGuard.guard();
    for (BlockState state : event.getChunk().getTileEntities(false)) {
      if (state instanceof BlockInventoryHolder holder
          && guard.protects(PaperPositions.toPosition(state.getLocation()))) {
        List<String> removed = inventories.purge(holder.getInventory());
        if (!removed.isEmpty()) {
          report(Optional.empty(), IN_A_HALL_CONTAINER, removed);
        }
      }
    }
  }

  private void report(Optional<PlayerId> player, String where, List<String> items) {
    TripwireReport listening = tripwire;
    if (listening != null) {
      listening.found(player, where, items);
    }
  }

  /** Blocks with a screen or an action that have no block entity or state to tell them by. */
  private static final java.util.Set<Material> WORKSTATIONS =
      java.util.EnumSet.of(
          Material.CRAFTING_TABLE,
          Material.STONECUTTER,
          Material.LOOM,
          Material.GRINDSTONE,
          Material.SMITHING_TABLE,
          Material.CARTOGRAPHY_TABLE,
          Material.ANVIL,
          Material.CHIPPED_ANVIL,
          Material.DAMAGED_ANVIL,
          Material.DRAGON_EGG);

  /**
   * Whether right-clicking a block does something, as a door, a lever or a chest does. Paper's own
   * {@code isInteractable} is deprecated as unreliable, so this reads the block's kind instead.
   */
  private static boolean isInteractable(Block block) {
    BlockData data = block.getBlockData();
    return block.getState(false) instanceof TileState
        || data instanceof Openable
        || data instanceof Powerable
        || data instanceof Bed
        || data instanceof NoteBlock
        || data instanceof Repeater
        || data instanceof Comparator
        || data instanceof Cake
        || data instanceof Lightable
        || data instanceof Levelled
        || data instanceof RespawnAnchor
        || Tag.FLOWER_POTS.isTagged(block.getType())
        || WORKSTATIONS.contains(block.getType());
  }

  /** Applies the core's decision on a player's intent to the event that raised it. */
  void decide(Cancellable event, Player player, Intent intent) {
    apply(event, player, intent);
  }

  /** The player behind a hit or a break: themselves, or whoever shot the projectile. */
  static @Nullable Player responsible(@Nullable Entity entity) {
    if (entity instanceof Player player) {
      return player;
    }
    return entity instanceof Projectile projectile
            && projectile.getShooter() instanceof Player shooter
        ? shooter
        : null;
  }

  private void apply(Cancellable event, Player player, Intent intent) {
    switch (handler.decide(intent)) {
      case Decision.Allow allow -> {}
      case Decision.AllowWithoutDrops withoutDrops -> {
        if (event instanceof BlockBreakEvent broken) {
          broken.setDropItems(false);
          broken.setExpToDrop(0);
        }
      }
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

  static PlayerId id(Player player) {
    return new PlayerId(player.getUniqueId());
  }
}
