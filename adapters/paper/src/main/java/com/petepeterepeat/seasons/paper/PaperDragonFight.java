// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.port.DragonFight;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.Optional;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.boss.DragonBattle;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * The End's dragon fight, and the guard on the egg while Seasons waits for it and takes it: no
 * dragon egg in the exit portal's column can teleport, be broken, be blown up, and no piston near
 * the portal moves, so the egg Seasons saw is the egg it takes.
 */
final class PaperDragonFight implements DragonFight, Listener {
  static final String THE_END = NamespacedKey.minecraft("the_end").asString();

  /** How far from the portal's column a piston is held still while the egg is guarded. */
  private static final int PISTON_REACH = 16;

  private volatile boolean guarded;

  @Override
  public boolean setPreviouslyKilled(boolean previouslyKilled) {
    Optional<DragonBattle> battle = battle();
    battle.ifPresent(b -> b.setPreviouslyKilled(previouslyKilled));
    return battle.isPresent();
  }

  @Override
  public boolean previouslyKilled() {
    return battle().map(DragonBattle::hasBeenPreviouslyKilled).orElse(false);
  }

  @Override
  public Optional<Position> exitPortal() {
    return battle()
        .flatMap(battle -> Optional.ofNullable(battle.getEndPortalLocation()))
        .map(PaperPositions::toPosition);
  }

  @Override
  public Optional<Position> eggOnPortal() {
    return battle()
        .flatMap(battle -> Optional.ofNullable(battle.getEndPortalLocation()))
        .map(PaperDragonFight::top)
        .filter(top -> top.getType() == Material.DRAGON_EGG)
        .map(top -> PaperPositions.toPosition(top.getLocation()));
  }

  @Override
  public boolean removeEgg(Position at) {
    Block block = PaperPositions.toLocation(at).getBlock();
    if (block.getType() != Material.DRAGON_EGG) {
      return false;
    }
    block.setType(Material.AIR);
    return true;
  }

  @Override
  public void guardEgg(boolean guarded) {
    this.guarded = guarded;
  }

  /** A dragon egg teleporting when clicked moves from its block to another. */
  @EventHandler(priority = EventPriority.LOWEST)
  void onEggTeleport(BlockFromToEvent event) {
    refuse(event, guarded(event.getBlock()));
  }

  @EventHandler(priority = EventPriority.LOWEST)
  void onBreak(BlockBreakEvent event) {
    refuse(event, guarded(event.getBlock()));
  }

  @EventHandler(priority = EventPriority.LOWEST)
  void onClick(PlayerInteractEvent event) {
    Block clicked = event.getClickedBlock();
    if (clicked != null && guarded(clicked)) {
      event.setUseInteractedBlock(Event.Result.DENY);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST)
  void onPush(BlockPistonExtendEvent event) {
    refuse(event, nearPortal(event.getBlock()));
  }

  @EventHandler(priority = EventPriority.LOWEST)
  void onPull(BlockPistonRetractEvent event) {
    refuse(event, nearPortal(event.getBlock()));
  }

  @EventHandler(priority = EventPriority.LOWEST)
  void onExplode(EntityExplodeEvent event) {
    event.blockList().removeIf(this::guarded);
  }

  @EventHandler(priority = EventPriority.LOWEST)
  void onExplode(BlockExplodeEvent event) {
    event.blockList().removeIf(this::guarded);
  }

  private static void refuse(Cancellable event, boolean refused) {
    if (refused) {
      event.setCancelled(true);
    }
  }

  /** Whether a block is a dragon egg in the exit portal's column while the egg is guarded. */
  private boolean guarded(Block block) {
    if (!guarded || block.getType() != Material.DRAGON_EGG) {
      return false;
    }
    Optional<Location> portal = portal(block);
    return portal.isPresent()
        && block.getX() == portal.get().getBlockX()
        && block.getZ() == portal.get().getBlockZ();
  }

  /** Whether a piston stands near the exit portal while the egg is guarded. */
  private boolean nearPortal(Block piston) {
    if (!guarded) {
      return false;
    }
    Optional<Location> portal = portal(piston);
    return portal.isPresent()
        && Math.abs(piston.getX() - portal.get().getBlockX()) <= PISTON_REACH
        && Math.abs(piston.getZ() - portal.get().getBlockZ()) <= PISTON_REACH;
  }

  /** The exit portal, if the block is in the End. */
  private static Optional<Location> portal(Block block) {
    if (!block.getWorld().getKey().asString().equals(THE_END)) {
      return Optional.empty();
    }
    return Optional.ofNullable(block.getWorld().getEnderDragonBattle())
        .flatMap(battle -> Optional.ofNullable(battle.getEndPortalLocation()));
  }

  @Override
  public NeutralItem eggItem() {
    return new NeutralItem(ItemStack.of(Material.DRAGON_EGG).serializeAsBytes());
  }

  /** The block the game puts the egg on: the top of the portal's column. */
  private static Block top(Location portal) {
    return portal
        .getWorld()
        .getHighestBlockAt(portal.getBlockX(), portal.getBlockZ(), HeightMap.MOTION_BLOCKING);
  }

  private static Optional<DragonBattle> battle() {
    return PaperPositions.world(THE_END)
        .flatMap(world -> Optional.ofNullable(world.getEnderDragonBattle()));
  }
}
