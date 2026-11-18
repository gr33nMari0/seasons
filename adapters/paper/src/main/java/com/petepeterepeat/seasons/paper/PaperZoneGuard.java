// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.ZoneGuard;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.Inventory;

/**
 * The zones' protection from Paper's events. What a player does goes to the core as an intent
 * through {@link PaperEvents}; what nobody in particular does (explosions, fire, pistons, liquids,
 * hoppers, dispensers, growth, endermen, portals) is refused when it would cross a blocked or
 * no-build zone's edge, or break or burn something inside one, as {@link ZoneGuard} says.
 */
final class PaperZoneGuard implements Listener {
  private final PaperEvents events;
  private ZoneGuard guard = ZoneGuard.NONE;

  PaperZoneGuard(PaperEvents events) {
    this.events = events;
  }

  void guard(ZoneGuard guard) {
    this.guard = guard;
  }

  private boolean protects(Location at) {
    return guard.protects(PaperPositions.toPosition(at));
  }

  private boolean protects(Block block) {
    return protects(block.getLocation());
  }

  private boolean allows(List<Position> blocks) {
    return guard.allows(blocks);
  }

  // Players, where the Hall's listener raises no intent

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onPrime(TNTPrimeEvent event) {
    Block block = event.getBlock();
    if (!protects(block)) {
      return;
    }
    Player player = PaperEvents.responsible(event.getPrimingEntity());
    if (player != null) {
      events.decide(
          event,
          player,
          new Intent.ChangeBlock(
              PaperEvents.id(player), position(block), Material.TNT.getKey().asString()));
    } else {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onFertilize(BlockFertilizeEvent event) {
    List<Position> touched = new ArrayList<>();
    touched.add(position(event.getBlock()));
    for (BlockState state : event.getBlocks()) {
      touched.add(PaperPositions.toPosition(state.getLocation()));
    }
    Player player = event.getPlayer();
    if (player != null && touched.stream().anyMatch(guard::protects)) {
      events.decide(
          event,
          player,
          new Intent.ChangeBlock(
              PaperEvents.id(player),
              position(event.getBlock()),
              Material.BONE_MEAL.getKey().asString()));
    } else if (!allows(touched)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onChangeBlock(EntityChangeBlockEvent event) {
    Block block = event.getBlock();
    Entity entity = event.getEntity();
    Player player = PaperEvents.responsible(entity);
    if (player != null) {
      if (protects(block)) {
        events.decide(
            event,
            player,
            new Intent.ChangeBlock(
                PaperEvents.id(player), position(block), event.getTo().getKey().asString()));
      }
      return;
    }
    // Endermen carry blocks off; anything else may change a block only on its own side of an edge.
    if ((entity instanceof Enderman && protects(block))
        || !allows(List.of(PaperPositions.toPosition(entity.getLocation()), position(block)))) {
      event.setCancelled(true);
    }
  }

  // Nobody in particular

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onEntityExplode(EntityExplodeEvent event) {
    event.blockList().removeIf(this::protects);
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onBlockExplode(BlockExplodeEvent event) {
    event.blockList().removeIf(this::protects);
  }

  /** Item frames and paintings inside stay up, unless a player's hit decides otherwise. */
  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onHangingBreak(HangingBreakEvent event) {
    Player player =
        event instanceof HangingBreakByEntityEvent byEntity
            ? PaperEvents.responsible(byEntity.getRemover())
            : null;
    if (player == null && protects(event.getEntity().getLocation())) {
      event.setCancelled(true);
    }
  }

  /** Explosions and fire hurt no armour stand, animal or other entity inside; players they may. */
  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onDamage(EntityDamageEvent event) {
    Entity entity = event.getEntity();
    if (entity instanceof Player) {
      return;
    }
    EntityDamageEvent.DamageCause cause = event.getCause();
    boolean explosionOrFire =
        cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
            || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
            || cause == EntityDamageEvent.DamageCause.FIRE
            || cause == EntityDamageEvent.DamageCause.FIRE_TICK
            || cause == EntityDamageEvent.DamageCause.LAVA;
    if (explosionOrFire && protects(entity.getLocation())) {
      event.setCancelled(true);
    }
  }

  /** Minecarts and boats inside break by nobody's hand; a player's hit is their intent. */
  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onVehicleDamage(VehicleDamageEvent event) {
    if (PaperEvents.responsible(event.getAttacker()) == null
        && protects(event.getVehicle().getLocation())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onBurn(BlockBurnEvent event) {
    if (protects(event.getBlock())) {
      event.setCancelled(true);
    }
  }

  /** Fire started by anything but a player; a player's is decided as their intent. */
  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onIgnite(BlockIgniteEvent event) {
    if (event.getPlayer() == null && protects(event.getBlock())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onSpread(BlockSpreadEvent event) {
    Material spreading = event.getNewState().getType();
    boolean fire = spreading == Material.FIRE || spreading == Material.SOUL_FIRE;
    if ((fire && protects(event.getBlock()))
        || !allows(List.of(position(event.getSource()), position(event.getBlock())))) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onStructureGrow(StructureGrowEvent event) {
    List<Position> touched = new ArrayList<>();
    touched.add(PaperPositions.toPosition(event.getLocation()));
    for (BlockState state : event.getBlocks()) {
      touched.add(PaperPositions.toPosition(state.getLocation()));
    }
    if (!allows(touched)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onPistonExtend(BlockPistonExtendEvent event) {
    piston(event, event.getBlocks());
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onPistonRetract(BlockPistonRetractEvent event) {
    piston(event, event.getBlocks());
  }

  /** The piston, every block it moves and where each goes must stay on one side of every edge. */
  private void piston(BlockPistonEvent event, List<Block> moved) {
    BlockFace direction = event.getDirection();
    List<Position> touched = new ArrayList<>();
    touched.add(position(event.getBlock()));
    touched.add(position(event.getBlock().getRelative(direction)));
    for (Block block : moved) {
      touched.add(position(block));
      touched.add(position(block.getRelative(direction)));
    }
    if (!allows(touched)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onFlow(BlockFromToEvent event) {
    if (!allows(List.of(position(event.getBlock()), position(event.getToBlock())))) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onDispense(BlockDispenseEvent event) {
    Block block = event.getBlock();
    Block target =
        block.getBlockData() instanceof Directional directional
            ? block.getRelative(directional.getFacing())
            : block;
    if (!allows(List.of(position(block), position(target)))) {
      event.setCancelled(true);
    }
  }

  /** No hopper moves items across an edge, either way. */
  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onMoveItem(InventoryMoveItemEvent event) {
    List<Position> touched = new ArrayList<>();
    add(touched, event.getSource());
    add(touched, event.getDestination());
    if (!allows(touched)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onHopperPickup(InventoryPickupItemEvent event) {
    List<Position> touched = new ArrayList<>();
    add(touched, event.getInventory());
    touched.add(PaperPositions.toPosition(event.getItem().getLocation()));
    if (!allows(touched)) {
      event.setCancelled(true);
    }
  }

  /** Where an inventory is: both halves of a double chest, or its holder's place. */
  private static void add(List<Position> touched, Inventory inventory) {
    List<Block> blocks = PaperChests.blocks(inventory);
    if (!blocks.isEmpty()) {
      blocks.forEach(block -> touched.add(position(block)));
      return;
    }
    Location at = inventory.getLocation();
    if (at != null) {
      touched.add(PaperPositions.toPosition(at));
    }
  }

  // Portals

  /** Nothing goes through a portal into a blocked zone, or out of one. */
  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onEntityPortal(EntityPortalEvent event) {
    Location to = event.getTo();
    if ((to != null && guard.blocks(PaperPositions.toPosition(to)))
        || guard.blocks(PaperPositions.toPosition(event.getFrom()))) {
      event.setCancelled(true);
    }
  }

  /**
   * A new portal, lit or made for the other end of a trip, never stands in a blocked zone. A
   * player's trip is refused with the reason; anything else's quietly.
   */
  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onPortalCreate(PortalCreateEvent event) {
    Position inside = null;
    for (BlockState state : event.getBlocks()) {
      Position at = PaperPositions.toPosition(state.getLocation());
      if (guard.blocks(at)) {
        inside = at;
        break;
      }
    }
    if (inside == null) {
      return;
    }
    Player player = PaperEvents.responsible(event.getEntity());
    if (player != null) {
      // Refused with the reason, unless they hold the bypass.
      events.decide(event, player, new Intent.CreatePortal(PaperEvents.id(player), inside));
    } else {
      event.setCancelled(true);
    }
  }

  private static Position position(Block block) {
    return PaperPositions.toPosition(block.getLocation());
  }
}
