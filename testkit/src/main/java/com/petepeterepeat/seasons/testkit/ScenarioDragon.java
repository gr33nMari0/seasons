// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.testkit;

import java.util.Locale;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.boss.DragonBattle;
import org.bukkit.command.CommandSender;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * {@code /scenario dragon}: the End's dragon fight, played as players would play it, for the dragon
 * egg scenarios. Bots can't aim, so the hits are made here, each a real hit with the player as its
 * causing entity, firing the same damage events as the game's own.
 *
 * <ul>
 *   <li>{@code info} prints {@code Dragon alive=… health=… killed=… phase=… portal=… egg=…}: the
 *       fight's dragon, the previously-killed flag, the respawn phase, the exit portal and the
 *       dragon egg on top of it, as positions or {@code none}
 *   <li>{@code respawn <player>} has the player place four end crystals from their inventory on the
 *       exit portal, each firing the same {@code EntityPlaceEvent} as the game's own, and asks the
 *       game to respawn the dragon after each as the end crystal item does ({@code
 *       DragonBattle#initiateRespawn()}, the game's own check: the dragon is dead and the four
 *       crystals stand there). The previously-killed flag is left alone
 *   <li>{@code hurt <player> <amount> <melee|arrow>} hurts the dragon by a player's hit, or by an
 *       arrow the player shot
 *   <li>{@code crystal <player>} puts an end crystal by the dragon's head and has the player break
 *       it: the crystal's real explosion hurts the dragon
 * </ul>
 */
final class ScenarioDragon {
  private ScenarioDragon() {}

  static void run(Plugin plugin, CommandSender sender, String[] args) {
    World end = plugin.getServer().getWorld(NamespacedKey.minecraft("the_end"));
    DragonBattle battle = end == null ? null : end.getEnderDragonBattle();
    if (end == null || battle == null) {
      sender.sendMessage("There is no End with a dragon fight");
      return;
    }
    switch (args.length > 1 ? args[1] : "") {
      case "info" -> info(sender, end, battle);
      case "respawn" -> respawn(plugin, sender, end, battle, args);
      case "hurt" -> hurt(plugin, sender, battle, args);
      case "crystal" -> crystal(plugin, sender, battle, args);
      default -> sender.sendMessage("Usage: /scenario dragon info|respawn|hurt|crystal ...");
    }
  }

  private static void info(CommandSender sender, World end, DragonBattle battle) {
    EnderDragon dragon = battle.getEnderDragon();
    Location portal = battle.getEndPortalLocation();
    sender.sendMessage(
        "Dragon alive="
            + (alive(battle) != null)
            + " health="
            + (dragon == null ? "none" : String.format(Locale.ROOT, "%.1f", dragon.getHealth()))
            + " killed="
            + battle.hasBeenPreviouslyKilled()
            + " phase="
            + battle.getRespawnPhase()
            + " portal="
            + (portal == null ? "none" : describe(portal.getBlock()))
            + " egg="
            + (portal == null ? "none" : egg(end, portal)));
  }

  /** The egg on top of the exit portal, where the game puts it, as a position or {@code none}. */
  private static String egg(World end, Location portal) {
    Block top =
        end.getHighestBlockAt(portal.getBlockX(), portal.getBlockZ(), HeightMap.MOTION_BLOCKING);
    return top.getType() == Material.DRAGON_EGG ? describe(top) : "none";
  }

  private static void respawn(
      Plugin plugin, CommandSender sender, World end, DragonBattle battle, String[] args) {
    Location portal = battle.getEndPortalLocation();
    Player player = args.length > 2 ? plugin.getServer().getPlayerExact(args[2]) : null;
    if (portal == null || player == null) {
      sender.sendMessage(
          "Can't respawn: " + (portal == null ? "the End has no exit portal" : "no such player"));
      return;
    }
    int[][] sides = {{3, 0}, {-3, 0}, {0, 3}, {0, -3}};
    for (int[] side : sides) {
      if (!player.getInventory().removeItem(ItemStack.of(Material.END_CRYSTAL)).isEmpty()) {
        sender.sendMessage("Can't respawn: " + player.getName() + " has no end crystal left");
        return;
      }
      Block on = portal.clone().add(side[0], 0, side[1]).getBlock();
      EnderCrystal crystal =
          end.createEntity(on.getLocation().add(0.5, 1, 0.5), EnderCrystal.class);
      crystal.setShowingBottom(false);
      EntityPlaceEvent event =
          new EntityPlaceEvent(crystal, player, on, BlockFace.UP, EquipmentSlot.HAND);
      plugin.getServer().getPluginManager().callEvent(event);
      if (event.isCancelled()) {
        sender.sendMessage("Can't respawn: placing an end crystal was refused");
        return;
      }
      end.addEntity(crystal);
      battle.initiateRespawn();
    }
    sender.sendMessage(
        battle.getRespawnPhase() != DragonBattle.RespawnPhase.NONE
            ? "Respawning the dragon"
            : "The dragon can't respawn now");
  }

  private static void hurt(
      Plugin plugin, CommandSender sender, DragonBattle battle, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[2]);
    EnderDragon dragon = alive(battle);
    if (player == null || dragon == null) {
      sender.sendMessage(
          "Hurt nothing: " + (player == null ? args[2] + " isn't online" : "no dragon"));
      return;
    }
    double amount = Double.parseDouble(args[3]);
    double before = dragon.getHealth();
    if (args[4].equals("arrow")) {
      Location from = dragon.getLocation().add(0, 3, 0);
      Arrow arrow =
          dragon
              .getWorld()
              .spawn(
                  from,
                  Arrow.class,
                  a -> {
                    a.setShooter(player);
                    a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                  });
      dragon.damage(
          amount,
          DamageSource.builder(DamageType.ARROW)
              .withCausingEntity(player)
              .withDirectEntity(arrow)
              .build());
      arrow.remove();
    } else {
      dragon.damage(
          amount,
          DamageSource.builder(DamageType.PLAYER_ATTACK)
              .withCausingEntity(player)
              .withDirectEntity(player)
              .build());
    }
    sender.sendMessage(
        String.format(
            Locale.ROOT,
            "Hurt the dragon as %s by %s: %.1f to %.1f",
            args[2],
            args[4],
            before,
            dragon.getHealth()));
  }

  private static void crystal(
      Plugin plugin, CommandSender sender, DragonBattle battle, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[2]);
    EnderDragon dragon = alive(battle);
    if (player == null || dragon == null) {
      sender.sendMessage(
          "No crystal: " + (player == null ? args[2] + " isn't online" : "no dragon"));
      return;
    }
    double before = dragon.getHealth();
    Location at = dragon.getLocation().add(0, 1, 0);
    EnderCrystal crystal =
        dragon.getWorld().spawn(at, EnderCrystal.class, c -> c.setShowingBottom(false));
    // A real attack, as the game handles a player's hit: the crystal explodes as the player's.
    player.resetCooldown();
    player.attack(crystal);
    sender.sendMessage(
        String.format(
            Locale.ROOT,
            "Set off a crystal as %s: %.1f to %.1f",
            args[2],
            before,
            dragon.getHealth()));
  }

  /**
   * The fight's dragon while it can still be hurt: a blow that would kill it leaves it at one
   * health point in its dying phase, which counts as dead.
   */
  private static @Nullable EnderDragon alive(DragonBattle battle) {
    EnderDragon dragon = battle.getEnderDragon();
    return dragon == null
            || dragon.isDead()
            || dragon.getHealth() <= 0
            || dragon.getPhase() == EnderDragon.Phase.DYING
        ? null
        : dragon;
  }

  private static String describe(Block block) {
    return block.getX() + "," + block.getY() + "," + block.getZ();
  }
}
