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
import org.bukkit.inventory.ItemStack;

final class PaperDragonFight implements DragonFight {
  static final String THE_END = NamespacedKey.minecraft("the_end").asString();

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
