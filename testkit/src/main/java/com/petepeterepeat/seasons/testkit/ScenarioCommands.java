// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.testkit;

import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.scenario.CrashPoint;
import com.petepeterepeat.seasons.core.scenario.ScenarioHooks;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.util.Vector;

/**
 * {@code /scenario}: the scenario hooks Seasons offers only on a server started with {@code
 * -Dseasons.scenario=true} (docs/adr/0003).
 *
 * <ul>
 *   <li>{@code crash-at <POINT> <player>} halts the server at that step of archiving them
 *   <li>{@code archive <player> <season>} prints what their archive and record hold
 *   <li>{@code card <player> <season>} prints their season card, its lines joined by {@code |}
 *   <li>{@code open-archive <viewer> <owner> <season>} opens an archive view
 *   <li>{@code place <player> <x> <y> <z> <block>} places a block as that player would, firing the
 *       same {@code BlockPlaceEvent}, because bots can't place blocks through the protocol
 *       translation (docs/adr/0001)
 *   <li>{@code break <player> <x> <y> <z>} breaks a block as that player would, firing {@code
 *       BlockBreakEvent}, for the same reason
 *   <li>{@code use <player> <x> <y> <z>} right-clicks a block as that player would, firing {@code
 *       PlayerInteractEvent}: the translation drops a bot's clicks on blocks too, though not on
 *       entities; a last word {@code sneak} clicks while sneaking
 *   <li>{@code move <player> <x> <y> <z> <walk|push|fall>} moves a player as their client would,
 *       firing {@code PlayerMoveEvent} and then putting them there unless it was cancelled: bots
 *       send no movement through the translation
 *   <li>{@code pearl <player> <x> <y> <z>} throws a real ender pearl from the player's eyes towards
 *       a block
 *   <li>{@code use-item <player>} right-clicks the air with the held item, firing {@code
 *       PlayerInteractEvent}
 *   <li>{@code consume <player>} eats the held item, firing {@code PlayerItemConsumeEvent}
 *   <li>{@code drop <player>} drops the held item as pressing Q would
 *   <li>{@code sit <player> <type> <x> <y> <z>} seats the player on the nearest entity of a type,
 *       firing {@code EntityMountEvent}
 *   <li>{@code glide <player> <command...>} puts an elytra on the player, sets them gliding and
 *       runs a command as them in the same tick
 * </ul>
 */
final class ScenarioCommands implements BasicCommand {
  private final Plugin plugin;

  ScenarioCommands(Plugin plugin) {
    this.plugin = plugin;
  }

  Optional<ScenarioHooks> hooks() {
    RegisteredServiceProvider<ScenarioHooks> registration =
        plugin.getServer().getServicesManager().getRegistration(ScenarioHooks.class);
    return Optional.ofNullable(registration).map(RegisteredServiceProvider::getProvider);
  }

  @Override
  public void execute(CommandSourceStack stack, String[] args) {
    CommandSender sender = stack.getSender();
    Optional<ScenarioHooks> found = hooks();
    if (found.isEmpty()) {
      sender.sendMessage("Scenario hooks are off: start the server with -Dseasons.scenario=true");
      return;
    }
    ScenarioHooks hooks = found.get();
    try {
      switch (args.length > 0 ? args[0] : "") {
        case "crash-at" -> {
          CrashPoint point = CrashPoint.valueOf(args[1].toUpperCase(Locale.ROOT).replace('-', '_'));
          hooks.arm(point, id(args[2]));
          sender.sendMessage("Armed crash point " + point + " for " + args[2]);
        }
        case "archive" -> {
          var _ =
              hooks
                  .summary(id(args[1]), args[1], Integer.parseInt(args[2]))
                  .whenComplete((lines, failure) ->
                      plugin
                          .getServer()
                          .getGlobalRegionScheduler()
                          .execute(
                              plugin,
                              () -> {
                                if (failure != null) {
                                  sender.sendMessage("Scenario archive failed: " + failure);
                                } else {
                                  lines.forEach(sender::sendMessage);
                                }
                              }));
        }
        case "card" -> {
          var _ =
              hooks
                  .card(id(args[1]), Integer.parseInt(args[2]))
                  .whenComplete((lines, failure) ->
                      plugin
                          .getServer()
                          .getGlobalRegionScheduler()
                          .execute(
                              plugin,
                              () ->
                                  sender.sendMessage(
                                      failure != null
                                          ? "Scenario card failed: " + failure
                                          : "Card of "
                                              + args[1]
                                              + " for Season "
                                              + args[2]
                                              + ": "
                                              + String.join(" | ", lines))));
        }
        case "open-archive" -> {
          Player viewer = plugin.getServer().getPlayerExact(args[1]);
          if (viewer == null) {
            sender.sendMessage(args[1] + " isn't online");
            return;
          }
          var _ =
              hooks
                  .openArchive(
                      new PlayerId(viewer.getUniqueId()), id(args[2]), Integer.parseInt(args[3]))
                  .whenComplete((opened, failure) ->
                      sender.sendMessage(
                          failure == null && opened
                              ? "Opened " + args[2] + "'s Season " + args[3] + " archive"
                              : "No archive to open: " + failure));
        }
        case "place" -> place(sender, args);
        case "break" -> breakBlock(sender, args);
        case "use" -> use(sender, args);
        case "move" -> move(sender, args);
        case "pearl" -> pearl(sender, args);
        case "use-item" -> useItem(sender, args);
        case "consume" -> consume(sender, args);
        case "drop" -> drop(sender, args);
        case "sit" -> sit(sender, args);
        case "glide" -> glide(sender, args);
        default ->
            sender.sendMessage(
                "Usage: /scenario crash-at|archive|card|open-archive|place|break|use|move|pearl"
                    + "|use-item|consume|drop|sit|glide ...");
      }
    } catch (RuntimeException e) {
      sender.sendMessage("Scenario command failed: " + e);
    }
  }

  private void place(CommandSender sender, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[1]);
    Material material = Material.matchMaterial(args[5]);
    if (player == null || material == null) {
      sender.sendMessage("Can't place " + args[5] + " as " + args[1]);
      return;
    }
    World world = player.getWorld();
    int x = Integer.parseInt(args[2]);
    int y = Integer.parseInt(args[3]);
    int z = Integer.parseInt(args[4]);
    Chunk chunk = world.getChunkAt(x >> 4, z >> 4);
    chunk.addPluginChunkTicket(plugin);
    Block block = world.getBlockAt(x, y, z);
    BlockPlaceEvent event =
        new BlockPlaceEvent(
            block,
            block.getState(),
            block.getRelative(BlockFace.DOWN),
            ItemStack.of(material),
            player,
            true,
            EquipmentSlot.HAND);
    plugin.getServer().getPluginManager().callEvent(event);
    if (event.isCancelled()) {
      sender.sendMessage("Placing " + material.getKey() + " was refused");
      return;
    }
    block.setType(material);
    sender.sendMessage("Placed " + material.getKey() + " at " + x + ", " + y + ", " + z);
  }

  private void breakBlock(CommandSender sender, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[1]);
    if (player == null) {
      sender.sendMessage("Can't break as " + args[1]);
      return;
    }
    Block block = loaded(player.getWorld(), args);
    Material was = block.getType();
    BlockBreakEvent event = new BlockBreakEvent(block, player);
    event.setDropItems(false);
    plugin.getServer().getPluginManager().callEvent(event);
    if (event.isCancelled()) {
      sender.sendMessage("Breaking " + was.getKey() + " was refused");
      return;
    }
    block.setType(Material.AIR);
    sender.sendMessage(
        "Broke "
            + was.getKey()
            + " at "
            + block.getX()
            + ", "
            + block.getY()
            + ", "
            + block.getZ());
  }

  private void use(CommandSender sender, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[1]);
    if (player == null) {
      sender.sendMessage("Can't use a block as " + args[1]);
      return;
    }
    Block block = loaded(player.getWorld(), args);
    boolean sneak = args.length > 5 && args[5].equals("sneak");
    boolean wasSneaking = player.isSneaking();
    if (sneak) {
      player.setSneaking(true);
    }
    PlayerInteractEvent event =
        new PlayerInteractEvent(
            player,
            Action.RIGHT_CLICK_BLOCK,
            player.getInventory().getItemInMainHand(),
            block,
            BlockFace.UP,
            EquipmentSlot.HAND);
    plugin.getServer().getPluginManager().callEvent(event);
    if (sneak) {
      player.setSneaking(wasSneaking);
    }
    sender.sendMessage(
        "Used "
            + block.getType().getKey()
            + " at "
            + block.getX()
            + ", "
            + block.getY()
            + ", "
            + block.getZ()
            + (event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY
                ? " (the click was taken over)"
                : ""));
  }

  private void move(CommandSender sender, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[1]);
    if (player == null) {
      sender.sendMessage("Can't move " + args[1]);
      return;
    }
    Location from = java.util.Objects.requireNonNull(player.getLocation());
    Location to =
        new Location(
            player.getWorld(),
            Double.parseDouble(args[2]),
            Double.parseDouble(args[3]),
            Double.parseDouble(args[4]),
            from.getYaw(),
            from.getPitch());
    to.getChunk().addPluginChunkTicket(plugin);
    PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);
    plugin.getServer().getPluginManager().callEvent(event);
    String how = args.length > 5 ? args[5] : "walk";
    if (event.isCancelled()) {
      sender.sendMessage("Moving " + args[1] + " (" + how + ") was refused");
      return;
    }
    player.teleport(event.getTo(), PlayerTeleportEvent.TeleportCause.UNKNOWN);
    sender.sendMessage("Moved " + args[1] + " (" + how + ") to " + describe(event.getTo()));
  }

  private void pearl(CommandSender sender, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[1]);
    if (player == null) {
      sender.sendMessage("Can't throw as " + args[1]);
      return;
    }
    Location eye = player.getEyeLocation();
    Vector towards =
        new Vector(
            Double.parseDouble(args[2]) + 0.5 - eye.getX(),
            0,
            Double.parseDouble(args[4]) + 0.5 - eye.getZ());
    double distance = towards.length();
    // About a second in the air: fast enough to cover the distance before it lands.
    Vector velocity = towards.normalize().multiply(distance / 22).setY(0.3);
    EnderPearl thrown = player.launchProjectile(EnderPearl.class, velocity);
    sender.sendMessage("Threw a pearl from " + describe(thrown.getLocation()));
  }

  private void useItem(CommandSender sender, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[1]);
    if (player == null) {
      sender.sendMessage("Can't use an item as " + args[1]);
      return;
    }
    ItemStack held = player.getInventory().getItemInMainHand();
    PlayerInteractEvent event =
        new PlayerInteractEvent(
            player, Action.RIGHT_CLICK_AIR, held, null, BlockFace.SELF, EquipmentSlot.HAND);
    plugin.getServer().getPluginManager().callEvent(event);
    boolean refused = event.useItemInHand() == org.bukkit.event.Event.Result.DENY;
    sender.sendMessage(
        "Using " + held.getType().getKey() + " was " + (refused ? "refused" : "allowed"));
  }

  private void consume(CommandSender sender, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[1]);
    if (player == null) {
      sender.sendMessage("Can't eat as " + args[1]);
      return;
    }
    ItemStack held = player.getInventory().getItemInMainHand();
    PlayerItemConsumeEvent event = new PlayerItemConsumeEvent(player, held, EquipmentSlot.HAND);
    plugin.getServer().getPluginManager().callEvent(event);
    sender.sendMessage(
        "Eating "
            + held.getType().getKey()
            + " was "
            + (event.isCancelled() ? "refused" : "allowed"));
  }

  private void drop(CommandSender sender, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[1]);
    if (player == null) {
      sender.sendMessage("Can't drop as " + args[1]);
      return;
    }
    boolean dropped = player.dropItem(false);
    sender.sendMessage("Dropped " + (dropped ? "an item" : "nothing") + " as " + args[1]);
  }

  private void sit(CommandSender sender, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[1]);
    NamespacedKey key = NamespacedKey.fromString(args[2]);
    EntityType type = key == null ? null : Registry.ENTITY_TYPE.get(key);
    if (player == null || type == null) {
      sender.sendMessage("Sitting as " + args[1] + " on " + args[2] + " is impossible");
      return;
    }
    Location at =
        new Location(
            player.getWorld(),
            Integer.parseInt(args[3]) + 0.5,
            Integer.parseInt(args[4]),
            Integer.parseInt(args[5]) + 0.5);
    Optional<Entity> seat =
        player.getWorld().getNearbyEntities(at, 2, 2, 2, e -> e.getType() == type).stream()
            .min(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(at)));
    if (seat.isEmpty()) {
      sender.sendMessage("Sitting on " + args[2] + " is impossible: there is none near");
      return;
    }
    boolean sat = seat.get().addPassenger(player);
    sender.sendMessage(
        sat
            ? "Sat " + args[1] + " on " + type.getKey()
            : "Sitting on " + type.getKey() + " was refused");
  }

  private void glide(CommandSender sender, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[1]);
    if (player == null) {
      sender.sendMessage("Can't glide as " + args[1]);
      return;
    }
    player.getInventory().setChestplate(ItemStack.of(Material.ELYTRA));
    player.setGliding(true);
    boolean gliding = player.isGliding();
    String command = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
    player.performCommand(command);
    sender.sendMessage("Gliding " + args[1] + " (" + gliding + ") ran /" + command);
  }

  private static String describe(Location at) {
    return String.format(Locale.ROOT, "%.1f, %.1f, %.1f", at.getX(), at.getY(), at.getZ());
  }

  /** The block at args 2 to 4, with its chunk kept loaded for the scenario. */
  private Block loaded(World world, String[] args) {
    int x = Integer.parseInt(args[2]);
    int y = Integer.parseInt(args[3]);
    int z = Integer.parseInt(args[4]);
    world.getChunkAt(x >> 4, z >> 4).addPluginChunkTicket(plugin);
    return world.getBlockAt(x, y, z);
  }

  @Override
  public String permission() {
    return "seasons.scenario";
  }

  /** A player's id from their name: online, cached, or as an offline-mode server derives it. */
  private PlayerId id(String name) {
    Player online = plugin.getServer().getPlayerExact(name);
    if (online != null) {
      return new PlayerId(online.getUniqueId());
    }
    OfflinePlayer cached = plugin.getServer().getOfflinePlayerIfCached(name);
    if (cached != null) {
      return new PlayerId(cached.getUniqueId());
    }
    return new PlayerId(
        UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)));
  }
}
