// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.port.HallGuard;
import com.petepeterepeat.seasons.core.port.Position;
import io.papermc.paper.event.entity.EntityBreakByEntityEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.Inventory;
import org.jspecify.annotations.Nullable;

/**
 * The Hall of Fame's protection from Paper's events. What a player does goes to the core as an
 * intent through {@link PaperEvents}; what nobody in particular does (explosions, fire, pistons,
 * liquids, hoppers, growth, spawning) is refused when it would change the Hall, as {@link
 * HallGuard} says.
 */
final class PaperHallGuard implements Listener {
  private final PaperEvents events;
  private HallGuard guard = HallGuard.NONE;

  PaperHallGuard(PaperEvents events) {
    this.events = events;
  }

  void guard(HallGuard guard) {
    this.guard = guard;
  }

  HallGuard guard() {
    return guard;
  }

  private boolean protects(Location at) {
    return guard.protects(PaperPositions.toPosition(at));
  }

  private boolean protects(Block block) {
    return protects(block.getLocation());
  }

  // Players

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onBucketEmpty(PlayerBucketEmptyEvent event) {
    events.decide(
        event,
        event.getPlayer(),
        new Intent.Bucket(
            PaperEvents.id(event.getPlayer()),
            position(event.getBlock()),
            event.getBucket().getKey().asString()));
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onBucketFill(PlayerBucketFillEvent event) {
    events.decide(
        event,
        event.getPlayer(),
        new Intent.Bucket(
            PaperEvents.id(event.getPlayer()),
            position(event.getBlockClicked()),
            Material.BUCKET.getKey().asString()));
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onIgnite(BlockIgniteEvent event) {
    Player player = event.getPlayer();
    if (player != null) {
      events.decide(
          event,
          player,
          new Intent.ChangeBlock(
              PaperEvents.id(player),
              position(event.getBlock()),
              event.getCause().name().toLowerCase(Locale.ROOT)));
    } else if (protects(event.getBlock())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onFertilize(BlockFertilizeEvent event) {
    boolean inHall = protects(event.getBlock());
    for (BlockState state : event.getBlocks()) {
      inHall = inHall || protects(state.getLocation());
    }
    if (!inHall) {
      return;
    }
    Player player = event.getPlayer();
    if (player == null) {
      event.setCancelled(true);
    } else {
      events.decide(
          event,
          player,
          new Intent.ChangeBlock(
              PaperEvents.id(player),
              position(event.getBlock()),
              Material.BONE_MEAL.getKey().asString()));
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onChangeBlock(EntityChangeBlockEvent event) {
    if (!protects(event.getBlock())) {
      return;
    }
    if (event.getEntity() instanceof Player player) {
      events.decide(
          event,
          player,
          new Intent.ChangeBlock(
              PaperEvents.id(player),
              position(event.getBlock()),
              event.getTo().getKey().asString()));
    } else {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onHangingPlace(HangingPlaceEvent event) {
    Player player = event.getPlayer();
    if (player != null) {
      placed(event, player, event.getEntity());
    } else if (protects(event.getEntity().getLocation())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onEntityPlace(EntityPlaceEvent event) {
    Player player = event.getPlayer();
    if (player != null) {
      placed(event, player, event.getEntity());
    } else if (protects(event.getEntity().getLocation())) {
      event.setCancelled(true);
    }
  }

  private void placed(org.bukkit.event.Cancellable event, Player player, Entity entity) {
    events.decide(
        event,
        player,
        new Intent.PlaceEntity(
            PaperEvents.id(player),
            PaperPositions.toPosition(entity.getLocation()),
            entity.getType().getKey().asString()));
  }

  /** Hanging entities broken by anything: by a player, the Hall's rules decide. */
  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onHangingBreak(HangingBreakEvent event) {
    Entity hanging = event.getEntity();
    Player player =
        event instanceof HangingBreakByEntityEvent byEntity
            ? PaperEvents.responsible(byEntity.getRemover())
            : null;
    broken(event, hanging, player);
  }

  /** Cushions and other entities broken rather than killed. */
  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onEntityBreak(EntityBreakByEntityEvent event) {
    broken(event, event.getEntity(), PaperEvents.responsible(event.getRemover()));
  }

  private void broken(org.bukkit.event.Cancellable event, Entity entity, @Nullable Player player) {
    if (player != null) {
      events.decide(
          event,
          player,
          new Intent.HurtEntity(
              PaperEvents.id(player),
              PaperPositions.toPosition(entity.getLocation()),
              entity.getType().getKey().asString()));
    } else if (protects(entity.getLocation())) {
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

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onBurn(BlockBurnEvent event) {
    if (protects(event.getBlock())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onSpread(BlockSpreadEvent event) {
    if (protects(event.getBlock())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onForm(BlockFormEvent event) {
    if (protects(event.getBlock())) {
      event.setCancelled(true);
    }
  }

  /** Ice melting, coral dying, snow going; fire may still go out. */
  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onFade(BlockFadeEvent event) {
    Material type = event.getBlock().getType();
    if (type != Material.FIRE && type != Material.SOUL_FIRE && protects(event.getBlock())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onGrow(BlockGrowEvent event) {
    if (protects(event.getBlock())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onStructureGrow(StructureGrowEvent event) {
    if (protects(event.getLocation())
        || event.getBlocks().stream().anyMatch(state -> protects(state.getLocation()))) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onLeavesDecay(LeavesDecayEvent event) {
    if (protects(event.getBlock())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onPrime(TNTPrimeEvent event) {
    if (protects(event.getBlock())) {
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

  /** The piston, every block it moves and where each goes must all be allowed together. */
  private void piston(BlockPistonEvent event, List<Block> moved) {
    BlockFace direction = event.getDirection();
    List<Position> touched = new ArrayList<>();
    touched.add(position(event.getBlock()));
    touched.add(position(event.getBlock().getRelative(direction)));
    for (Block block : moved) {
      touched.add(position(block));
      touched.add(position(block.getRelative(direction)));
    }
    if (!guard.allows(touched)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onFlow(BlockFromToEvent event) {
    if (!guard.allows(List.of(position(event.getBlock()), position(event.getToBlock())))) {
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
    if (protects(block) || protects(target)) {
      event.setCancelled(true);
    }
  }

  /** Nothing pulls items out of a Hall container, or pushes them in. */
  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onMoveItem(InventoryMoveItemEvent event) {
    if (inHall(event.getSource()) || inHall(event.getDestination())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onHopperPickup(InventoryPickupItemEvent event) {
    if (inHall(event.getInventory()) || protects(event.getItem().getLocation())) {
      event.setCancelled(true);
    }
  }

  private boolean inHall(Inventory inventory) {
    Location at = inventory.getLocation();
    return at != null && protects(at);
  }

  /** Only Seasons, commands and other plugins put creatures in the Hall. */
  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onSpawn(CreatureSpawnEvent event) {
    CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
    if (reason != CreatureSpawnEvent.SpawnReason.CUSTOM
        && reason != CreatureSpawnEvent.SpawnReason.COMMAND
        && protects(event.getLocation())) {
      event.setCancelled(true);
    }
  }

  /** Projectiles change nothing in the Hall: no broken pots, lit candles or pressed targets. */
  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onProjectileHit(ProjectileHitEvent event) {
    Block hit = event.getHitBlock();
    if (hit != null && protects(hit)) {
      event.setCancelled(true);
    }
  }

  private static Position position(Block block) {
    return PaperPositions.toPosition(block.getLocation());
  }
}
