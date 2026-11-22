// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.testkit;

import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.scenario.CrashPoint;
import com.petepeterepeat.seasons.core.scenario.ScenarioHooks;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.entity.TeleportFlag;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boat;
import org.bukkit.entity.ChestedHorse;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.boat.OakBoat;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.util.Vector;

/**
 * {@code /scenario}: the scenario hooks Seasons offers only on a server started with {@code
 * -Dseasons.scenario=true} (docs/adr/0003).
 *
 * <ul>
 *   <li>{@code crash-at <POINT> <player>} halts the server at that step of archiving them
 *   <li>{@code save-at <POINT> <player>} saves every world to disk when they reach that step,
 *       without stopping, so a later crash point finds the world as it was there
 *   <li>{@code act-at <POINT> <player> <give|empty> [crash]} when archiving them reaches that step,
 *       gives them a stone, or empties their inventory and ender chest, and saves their file, on
 *       the server thread before archiving goes on; with {@code crash}, halts the server then
 *   <li>{@code hold <player>} holds the player as a transaction such as a vault withdrawal does,
 *       until {@code release <player>}
 *   <li>{@code archive <player> <season>} prints what their archive and record hold
 *   <li>{@code card <player> <season>} prints their season card, its lines joined by {@code |}
 *   <li>{@code open-archive <viewer> <owner> <season>} opens an archive view
 *   <li>{@code place <player> <x> <y> <z> <block>} places a block, with its state if given, as that
 *       player would, firing the same {@code BlockPlaceEvent}, because bots can't place blocks
 *       through the protocol translation (docs/adr/0001)
 *   <li>{@code break <player> <x> <y> <z> [drops]} breaks a block as that player would, firing
 *       {@code BlockBreakEvent}, for the same reason; with {@code drops} it drops what survival
 *       would unless a listener turns drops off
 *   <li>{@code use <player> <x> <y> <z>} right-clicks a block as that player would, firing {@code
 *       PlayerInteractEvent}: the translation drops a bot's clicks on blocks too, though not on
 *       entities; a last word {@code sneak} clicks while sneaking
 *   <li>{@code move <player> <x> <y> <z> <walk|push|fall> [back]} moves a player as their client
 *       would, firing {@code PlayerMoveEvent} and then putting them there unless it was cancelled,
 *       with a teleport no other plugin sees: bots send no movement through the translation. With
 *       {@code back}, a second move returns them in the same tick, too quickly for any sweep
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
 *   <li>{@code open <player> <x> <y> <z>} right-clicks a block as {@code use} does and, if the
 *       click goes through, opens a container's inventory as the game would, firing {@code
 *       InventoryOpenEvent}; it reports whether the player has the real inventory open, a copy or
 *       nothing
 *   <li>{@code place-entity <player> <type> <x> <y> <z>} places an entity such as a cushion as the
 *       player would, firing {@code EntityPlaceEvent}
 *   <li>{@code hit <player> <type> <x> <y> <z>} has the player attack the nearest entity of a type,
 *       a real attack with all its events
 *   <li>{@code move ... fly} moves a player as {@code walk} does, gliding with an elytra
 *   <li>{@code place ... consume} takes the block placed from what the player carries, as the game
 *       does, and is refused if they carry none
 *   <li>{@code ride <player> <x> <y> <z>} seats the player in a new boat and moves the boat to a
 *       spot with them in it, firing {@code VehicleMoveEvent} as a moving boat does
 *   <li>{@code chorus <player> <x> <y> <z>} teleports the player with the cause eating chorus fruit
 *       gives, since where the fruit lands is random
 *   <li>{@code portal <player> <dimension> <x> <y> <z>} takes the player through a nether portal to
 *       a spot: {@code PlayerPortalEvent}, then the teleport
 *   <li>{@code portal-pair <player> <dimension> <x> <y> <z>} makes the obsidian frame the game
 *       would build at a spot for the other end of the player's trip, firing {@code
 *       PortalCreateEvent}
 *   <li>{@code bucket <player> <x> <y> <z> <water|lava|fill>} empties or fills a bucket at a block
 *       as the player, firing the bucket events
 *   <li>{@code ignite <player> <x> <y> <z>} lights a block with flint and steel as the player: TNT
 *       is primed ({@code TNTPrimeEvent}), anything else set alight ({@code BlockIgniteEvent})
 *   <li>{@code permit <player> <node>} gives the player a permission until they leave
 *   <li>{@code workstation <player> <anvil|stonecutter|merchant> <item> <count>} opens that menu
 *       for the player, as if they had clicked an anvil, a stonecutter or a villager, and puts a
 *       stack in each of its input slots, which the game hands back to the player when it closes
 *   <li>{@code cursor <player> <item> <count>} puts a stack on the player's cursor, as picking it
 *       up in an open menu would
 *   <li>{@code tagged <player> <shulker|bundle>} gives the player what an archived copy that got
 *       out would look like: a shulker box tagged {@code seasons:archived} holding untagged
 *       diamonds, in their main hand; or an untagged bundle holding a tagged diamond and a stick
 *   <li>{@code place ... held} places the item in the player's main hand, as the game does
 *   <li>{@code scroll <player> <slot> [sneak]} scrolls the hotbar to a slot as the player, firing
 *       {@code PlayerItemHeldEvent}, and holds that slot unless it is cancelled
 *   <li>{@code swap-hands <player>} presses swap-hands as the player, firing {@code
 *       PlayerSwapHandItemsEvent}, and swaps unless it is cancelled
 *   <li>{@code console <command...>} runs a command as a sender that isn't a player, as the console
 *       would, and passes what it is told back prefixed "Console: "
 *   <li>{@code vault-return <player> <item> <count>} hands a stack back to the player on their
 *       vault's Returned page, as a curator's return or an undelivered dragon egg will
 *   <li>{@code tame <tag> <player>} tames the entity with a scoreboard tag to a player
 *   <li>{@code chest <tag> <on|off>} puts a chest on a donkey, mule or llama, or takes it off
 *   <li>{@code leash <tag> <holder tag>} puts an animal on a lead held by another entity
 *   <li>{@code forget-pet <tag>} takes Seasons' mark off a registered pet, so it can go unnoticed,
 *       as when its chunks are regenerated
 *   <li>{@code dragon info|respawn|hurt|crystal ...} plays the End's dragon fight; see {@link
 *       ScenarioDragon}
 *   <li>{@code reset} resets a shared scenario server to a fresh boot and reports any leak; the
 *       runner asks for this itself between scenarios, so this is for trying it by hand
 *   <li>{@code clock [+<duration>]} prints Seasons' time, after moving it forward by a duration
 *       such as {@code +30s}, {@code +10m}, {@code +2h}, {@code +7d} or {@code +1500ms}: whatever
 *       came due in the time skipped runs at once
 *   <li>{@code sync <id> [ticks] [player...]} answers {@code Synced <id>} once the current tick (or
 *       that many more) has finished, to the sender and to each player named, so anything sent to
 *       them before has arrived when they read it
 *   <li>{@code bluemap} prints every marker in Seasons' marker sets on every BlueMap map, read
 *       through BlueMap's API, on a scenario server that has BlueMap (docs/adr/0004); see {@link
 *       ScenarioBlueMap}
 * </ul>
 */
final class ScenarioCommands implements BasicCommand, Listener {
  private final Plugin plugin;
  private final Supplier<CompletableFuture<List<String>>> reset;

  /** Players {@code /scenario move} is putting where their client would have gone. */
  private final Set<UUID> clientMoving = new HashSet<>();

  ScenarioCommands(Plugin plugin, Supplier<CompletableFuture<List<String>>> reset) {
    this.plugin = plugin;
    this.reset = reset;
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
        case "save-at" -> {
          CrashPoint point = CrashPoint.valueOf(args[1].toUpperCase(Locale.ROOT).replace('-', '_'));
          hooks.armSave(point, id(args[2]));
          sender.sendMessage("Armed save point " + point + " for " + args[2]);
        }
        case "act-at" -> actAt(sender, hooks, args);
        case "hold" ->
            sender.sendMessage(
                hooks.hold(id(args[1])) ? "Holding " + args[1] : args[1] + " is held already");
        case "release" -> {
          hooks.release(id(args[1]));
          sender.sendMessage("Released " + args[1]);
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
        case "open" -> open(sender, args);
        case "place-entity" -> placeEntity(sender, args);
        case "hit" -> hit(sender, args);
        case "ride" -> ride(sender, args);
        case "chorus" -> chorus(sender, args);
        case "portal" -> portal(sender, args);
        case "portal-pair" -> portalPair(sender, args);
        case "bucket" -> bucket(sender, args);
        case "ignite" -> ignite(sender, args);
        case "permit" -> permit(sender, args);
        case "workstation" -> workstation(sender, args);
        case "cursor" -> cursor(sender, args);
        case "tagged" -> tagged(sender, args);
        case "console" -> console(sender, args);
        case "swap-hands" -> swapHands(sender, args);
        case "scroll" -> scroll(sender, args);
        case "vault-return" -> vaultReturn(sender, hooks, args);
        case "tame" -> tame(sender, args);
        case "stat" -> stat(sender, args);
        case "chest" -> chest(sender, args);
        case "leash" -> leash(sender, args);
        case "forget-pet" -> forgetPet(sender, args);
        case "dragon" -> ScenarioDragon.run(plugin, sender, args);
        case "reset" -> {
          var _ =
              reset
                  .get()
                  .whenComplete((leaks, failure) ->
                      sender.sendMessage(
                          failure != null
                              ? "Reset failed: " + failure
                              : leaks.isEmpty()
                                  ? "Reset: as a fresh boot"
                                  : "Reset leaked: " + leaks));
        }
        case "clock" -> {
          Instant now = args.length > 1 ? hooks.advance(duration(args[1])) : hooks.now();
          sender.sendMessage(
              "Clock " + now + " offset " + Duration.between(Instant.now(), now).toMillis());
        }
        case "sync" -> sync(sender, args);
        case "bluemap" -> {
          // BlueMap's classes are there only when it is installed.
          if (plugin.getServer().getPluginManager().getPlugin("BlueMap") == null) {
            sender.sendMessage(ScenarioBlueMap.OFF);
          } else {
            ScenarioBlueMap.print(sender, plugin.getServer());
          }
        }
        default ->
            sender.sendMessage(
                "Usage: /scenario crash-at|save-at|archive|card|open-archive|place|break|use|move|pearl"
                    + "|use-item|consume|drop|sit|glide|open|place-entity|hit|ride|chorus|portal"
                    + "|portal-pair|bucket|ignite|permit|console|swap-hands|scroll|vault-return|tame|chest|leash|forget-pet"
                    + "|dragon|reset|clock|sync|bluemap ...");
      }
    } catch (RuntimeException e) {
      sender.sendMessage("Scenario command failed: " + e);
    }
  }

  private void place(CommandSender sender, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[1]);
    // A block, or a block with its state, such as minecraft:chest[facing=north,type=right].
    BlockData data;
    try {
      data = plugin.getServer().createBlockData(args[5]);
    } catch (IllegalArgumentException e) {
      sender.sendMessage("Can't place " + args[5] + " as " + args[1]);
      return;
    }
    Material material = data.getMaterial();
    if (player == null) {
      sender.sendMessage("Can't place " + args[5] + " as " + args[1]);
      return;
    }
    boolean consume = args.length > 6 && args[6].equals("consume");
    // "held": the item in the player's hand is the one placed, as the game's own placing has it.
    boolean held = args.length > 6 && args[6].equals("held");
    if (consume && !player.getInventory().contains(material)) {
      sender.sendMessage("Placing " + material.getKey() + " was refused: " + args[1] + " has none");
      return;
    }
    World world = player.getWorld();
    int x = Integer.parseInt(args[2]);
    int y = Integer.parseInt(args[3]);
    int z = Integer.parseInt(args[4]);
    Chunk chunk = world.getChunkAt(x >> 4, z >> 4);
    chunk.addPluginChunkTicket(plugin);
    Block block = world.getBlockAt(x, y, z);
    // As the game does: the block is there while the event runs, and taken back if refused.
    BlockState replaced = block.getState();
    block.setBlockData(data, false);
    BlockPlaceEvent event =
        new BlockPlaceEvent(
            block,
            replaced,
            block.getRelative(BlockFace.DOWN),
            held ? player.getInventory().getItemInMainHand().clone() : ItemStack.of(material),
            player,
            true,
            EquipmentSlot.HAND);
    plugin.getServer().getPluginManager().callEvent(event);
    if (event.isCancelled()) {
      replaced.update(true, false);
      sender.sendMessage("Placing " + material.getKey() + " was refused");
      return;
    }
    if (consume) {
      player.getInventory().removeItem(ItemStack.of(material));
    }
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
    // A last word "drops" breaks it as survival does, dropping what it drops unless a listener
    // says otherwise; without it nothing drops, as before.
    boolean drops = args.length > 5 && args[5].equals("drops");
    BlockBreakEvent event = new BlockBreakEvent(block, player);
    event.setDropItems(drops);
    plugin.getServer().getPluginManager().callEvent(event);
    if (event.isCancelled()) {
      sender.sendMessage("Breaking " + was.getKey() + " was refused");
      return;
    }
    if (event.isDropItems()) {
      block.breakNaturally();
    } else {
      block.setType(Material.AIR);
    }
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
    // Words after the position: sneak, left (a left click) and face=<side> (default up).
    java.util.List<String> words =
        java.util.Arrays.asList(args).subList(Math.min(5, args.length), args.length);
    boolean sneak = words.contains("sneak");
    boolean left = words.contains("left");
    BlockFace face =
        words.stream()
            .filter(w -> w.startsWith("face="))
            .map(w -> BlockFace.valueOf(w.substring(5).toUpperCase(java.util.Locale.ROOT)))
            .findFirst()
            .orElse(BlockFace.UP);
    boolean wasSneaking = player.isSneaking();
    if (sneak) {
      player.setSneaking(true);
    }
    PlayerInteractEvent event =
        new PlayerInteractEvent(
            player,
            left ? Action.LEFT_CLICK_BLOCK : Action.RIGHT_CLICK_BLOCK,
            player.getInventory().getItemInMainHand(),
            block,
            face,
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
    String how = args.length > 5 ? args[5] : "walk";
    boolean back = args.length > 6 && args[6].equals("back");
    if (how.equals("fly")) {
      player.getInventory().setChestplate(ItemStack.of(Material.ELYTRA));
      player.setGliding(true);
    }
    if (!clientMove(player, from, to)) {
      sender.sendMessage("Moving " + args[1] + " (" + how + ") was refused");
      return;
    }
    if (back && !clientMove(player, to, from)) {
      sender.sendMessage("Moving " + args[1] + " back (" + how + ") was refused");
      return;
    }
    sender.sendMessage(
        "Moved "
            + args[1]
            + " ("
            + how
            + ") to "
            + describe(to)
            + (back ? " and straight back to " + describe(from) : ""));
  }

  /**
   * One move as the server sees a client's: {@code PlayerMoveEvent}, then the player is there.
   * Putting them there takes a teleport, which other plugins never see (see {@link
   * #hideClientMove}), since a real client's movement fires no teleport event.
   */
  private boolean clientMove(Player player, Location from, Location to) {
    PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);
    plugin.getServer().getPluginManager().callEvent(event);
    if (event.isCancelled()) {
      return false;
    }
    clientMoving.add(player.getUniqueId());
    try {
      player.teleport(event.getTo(), PlayerTeleportEvent.TeleportCause.UNKNOWN);
    } finally {
      clientMoving.remove(player.getUniqueId());
    }
    return true;
  }

  /** Cancels a client move's teleport before any other plugin sees it... */
  @EventHandler(priority = EventPriority.LOWEST)
  void hideClientMove(PlayerTeleportEvent event) {
    if (clientMoving.contains(event.getPlayer().getUniqueId())) {
      event.setCancelled(true);
    }
  }

  /** ...and lets it go ahead once they all have, so the player still ends up there. */
  @EventHandler(priority = EventPriority.HIGHEST)
  void applyClientMove(PlayerTeleportEvent event) {
    if (clientMoving.contains(event.getPlayer().getUniqueId())) {
      event.setCancelled(false);
    }
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

  private void open(CommandSender sender, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[1]);
    if (player == null) {
      sender.sendMessage("Can't open as " + args[1]);
      return;
    }
    Block block = loaded(player.getWorld(), args);
    PlayerInteractEvent event =
        new PlayerInteractEvent(
            player,
            Action.RIGHT_CLICK_BLOCK,
            player.getInventory().getItemInMainHand(),
            block,
            BlockFace.UP,
            EquipmentSlot.HAND);
    plugin.getServer().getPluginManager().callEvent(event);
    // What the game does next when the click goes through: a container opens its inventory.
    if (event.useInteractedBlock() != org.bukkit.event.Event.Result.DENY
        && block.getState() instanceof Container container
        && ownInventoryOnly(player)) {
      player.openInventory(container.getInventory());
    }
    // A plugin may open something else instead a tick later, as Seasons opens a view-only copy.
    String type = block.getType().getKey().toString();
    player
        .getScheduler()
        .runDelayed(
            plugin,
            task -> {
              InventoryView view = player.getOpenInventory();
              String opened;
              if (ownInventoryOnly(player)) {
                opened = "nothing";
              } else if (view.getTopInventory().getHolder(false) instanceof BlockInventoryHolder
                  || view.getTopInventory().getHolder(false) instanceof DoubleChest) {
                opened = "the real one";
              } else {
                opened = "a copy";
              }
              sender.sendMessage("Opened " + type + " as " + args[1] + ": " + opened);
            },
            null,
            3);
  }

  /** Whether a player has no menu open: only their own inventory, survival's or creative's. */
  private static boolean ownInventoryOnly(Player player) {
    InventoryType type = player.getOpenInventory().getType();
    return type == InventoryType.CRAFTING || type == InventoryType.CREATIVE;
  }

  private void placeEntity(CommandSender sender, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[1]);
    NamespacedKey key = NamespacedKey.fromString(args[2]);
    EntityType type = key == null ? null : Registry.ENTITY_TYPE.get(key);
    Class<? extends Entity> kind = type == null ? null : type.getEntityClass();
    if (player == null || type == null || kind == null) {
      sender.sendMessage("Placing " + args[2] + " as " + args[1] + " is impossible");
      return;
    }
    Block block = loaded(player.getWorld(), shifted(args));
    Location at = block.getLocation().add(0.5, 0, 0.5);
    Entity entity = player.getWorld().createEntity(at, kind);
    EntityPlaceEvent event =
        new EntityPlaceEvent(
            entity, player, block.getRelative(BlockFace.DOWN), BlockFace.UP, EquipmentSlot.HAND);
    plugin.getServer().getPluginManager().callEvent(event);
    if (event.isCancelled()) {
      sender.sendMessage("Placing " + type.getKey() + " was refused");
      return;
    }
    if (entity instanceof org.bukkit.entity.LivingEntity) {
      // As the game's own placing of an armour stand item adds it: with the DEFAULT spawn reason,
      // which the creature spawn event then sees.
      player
          .getWorld()
          .spawn(at, kind, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.DEFAULT);
    } else {
      player.getWorld().addEntity(entity);
    }
    sender.sendMessage("Placed " + type.getKey() + " at " + describe(at));
  }

  private void hit(CommandSender sender, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[1]);
    NamespacedKey key = NamespacedKey.fromString(args[2]);
    EntityType type = key == null ? null : Registry.ENTITY_TYPE.get(key);
    if (player == null || type == null) {
      sender.sendMessage("Hit nothing: " + args[1] + " or " + args[2] + " is unknown");
      return;
    }
    Block block = loaded(player.getWorld(), shifted(args));
    Location at = block.getLocation().add(0.5, 0, 0.5);
    Optional<Entity> target =
        player.getWorld().getNearbyEntities(at, 2, 2, 2, e -> e.getType() == type).stream()
            .min(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(at)));
    if (target.isEmpty()) {
      sender.sendMessage("Hit nothing: there is no " + type.getKey() + " near");
      return;
    }
    // A real attack, at full strength, as the game handles a player's hit.
    player.resetCooldown();
    player.attack(target.get());
    sender.sendMessage("Hit " + type.getKey() + " as " + args[1]);
  }

  // Keeping the passenger on a teleport is deprecated, but this Paper still needs asking for it.
  @SuppressWarnings("removal")
  private void ride(CommandSender sender, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[1]);
    if (player == null) {
      sender.sendMessage("Riding as " + args[1] + " is impossible");
      return;
    }
    Location from = java.util.Objects.requireNonNull(player.getLocation());
    Location to = at(player.getWorld(), args, 2);
    to.getChunk().addPluginChunkTicket(plugin);
    Boat boat = player.getWorld().spawn(from, OakBoat.class);
    boat.addPassenger(player);
    plugin.getServer().getPluginManager().callEvent(new VehicleMoveEvent(boat, from, to));
    if (!boat.getPassengers().contains(player)) {
      sender.sendMessage("Riding " + args[1] + " to " + describe(to) + " was stopped");
      boat.teleport(to);
      return;
    }
    boat.teleport(to, TeleportFlag.EntityState.RETAIN_PASSENGERS);
    sender.sendMessage("Rode " + args[1] + " in a boat to " + describe(to));
  }

  private void chorus(CommandSender sender, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[1]);
    if (player == null) {
      sender.sendMessage("Chorus teleport of " + args[1] + " was refused: not online");
      return;
    }
    Location to = at(player.getWorld(), args, 2);
    to.getChunk().addPluginChunkTicket(plugin);
    boolean went = player.teleport(to, PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT);
    sender.sendMessage("Chorus teleport of " + args[1] + (went ? " went" : " was refused"));
  }

  private void portal(CommandSender sender, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[1]);
    World world = dimension(args[2]);
    if (player == null || world == null) {
      sender.sendMessage("Portal trip of " + args[1] + " was refused: no such player or dimension");
      return;
    }
    Location to = at(world, args, 3);
    to.getChunk().addPluginChunkTicket(plugin);
    PlayerPortalEvent event =
        new PlayerPortalEvent(
            player,
            java.util.Objects.requireNonNull(player.getLocation()),
            to,
            PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);
    plugin.getServer().getPluginManager().callEvent(event);
    boolean went =
        !event.isCancelled()
            && player.teleport(event.getTo(), PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);
    sender.sendMessage("Portal trip of " + args[1] + (went ? " went" : " was refused"));
  }

  private void portalPair(CommandSender sender, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[1]);
    World world = dimension(args[2]);
    if (player == null || world == null) {
      sender.sendMessage("Making a portal for " + args[1] + " is impossible");
      return;
    }
    Location base = at(world, args, 3);
    base.getChunk().addPluginChunkTicket(plugin);
    // A frame four wide and five tall along x, as the game builds one, with portal blocks inside.
    java.util.List<BlockState> blocks = new java.util.ArrayList<>();
    for (int dx = 0; dx < 4; dx++) {
      for (int dy = 0; dy < 5; dy++) {
        Block block = base.clone().add(dx, dy, 0).getBlock();
        boolean frame = dx == 0 || dx == 3 || dy == 0 || dy == 4;
        BlockState state = block.getState();
        state.setType(frame ? Material.OBSIDIAN : Material.NETHER_PORTAL);
        blocks.add(state);
      }
    }
    PortalCreateEvent event =
        new PortalCreateEvent(blocks, world, player, PortalCreateEvent.CreateReason.NETHER_PAIR);
    plugin.getServer().getPluginManager().callEvent(event);
    if (event.isCancelled()) {
      sender.sendMessage("Making a portal for " + args[1] + " was refused");
      return;
    }
    blocks.forEach(state -> state.update(true, false));
    sender.sendMessage("Made a portal for " + args[1] + " at " + describe(base));
  }

  private void bucket(CommandSender sender, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[1]);
    if (player == null) {
      sender.sendMessage("Bucket of " + args[1] + " was refused: not online");
      return;
    }
    Block block = loaded(player.getWorld(), args);
    String what = args[5];
    Block against = block.getRelative(BlockFace.DOWN);
    org.bukkit.event.Cancellable event;
    Material result;
    if (what.equals("fill")) {
      PlayerBucketFillEvent fill =
          new PlayerBucketFillEvent(
              player,
              block,
              block,
              BlockFace.UP,
              Material.BUCKET,
              ItemStack.of(Material.BUCKET),
              EquipmentSlot.HAND);
      event = fill;
      result = Material.AIR;
      plugin.getServer().getPluginManager().callEvent(fill);
    } else {
      Material bucket = what.equals("lava") ? Material.LAVA_BUCKET : Material.WATER_BUCKET;
      PlayerBucketEmptyEvent empty =
          new PlayerBucketEmptyEvent(
              player,
              block,
              against,
              BlockFace.UP,
              bucket,
              ItemStack.of(bucket),
              EquipmentSlot.HAND);
      event = empty;
      result = what.equals("lava") ? Material.LAVA : Material.WATER;
      plugin.getServer().getPluginManager().callEvent(empty);
    }
    if (event.isCancelled()) {
      sender.sendMessage(
          "Bucket of " + what + " at " + describe(block.getLocation()) + " was refused");
      return;
    }
    block.setType(result);
    sender.sendMessage("Bucket of " + what + " at " + describe(block.getLocation()) + " went");
  }

  private void ignite(CommandSender sender, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[1]);
    if (player == null) {
      sender.sendMessage("Lighting as " + args[1] + " is impossible");
      return;
    }
    Block block = loaded(player.getWorld(), args);
    if (block.getType() == Material.TNT) {
      TNTPrimeEvent event = new TNTPrimeEvent(block, TNTPrimeEvent.PrimeCause.PLAYER, player, null);
      plugin.getServer().getPluginManager().callEvent(event);
      if (event.isCancelled()) {
        sender.sendMessage("Lighting the TNT at " + describe(block.getLocation()) + " was refused");
        return;
      }
      block.setType(Material.AIR);
      block.getWorld().spawn(block.getLocation().add(0.5, 0, 0.5), TNTPrimed.class);
      sender.sendMessage("Lit the TNT at " + describe(block.getLocation()));
      return;
    }
    BlockIgniteEvent event =
        new BlockIgniteEvent(block, BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL, player);
    plugin.getServer().getPluginManager().callEvent(event);
    if (event.isCancelled()) {
      sender.sendMessage("Lighting " + describe(block.getLocation()) + " was refused");
      return;
    }
    block.setType(Material.FIRE);
    sender.sendMessage("Lit " + describe(block.getLocation()));
  }

  /**
   * Runs a command as a sender that isn't a player, as the console does, and passes each line it is
   * told back to the sender, prefixed "Console: ".
   */
  private void console(CommandSender sender, String[] args) {
    String command = String.join(" ", java.util.Arrays.asList(args).subList(1, args.length));
    CommandSender console =
        plugin
            .getServer()
            .createCommandSender(feedback ->
                sender.sendMessage(
                    "Console: "
                        + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                            .plainText()
                            .serialize(feedback)));
    plugin.getServer().dispatchCommand(console, command);
  }

  /**
   * Scrolls the hotbar to a slot as the player, firing PlayerItemHeldEvent; held unless cancelled.
   */
  private void scroll(CommandSender sender, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[1]);
    if (player == null) {
      sender.sendMessage("Can't scroll for " + args[1]);
      return;
    }
    int slot = Integer.parseInt(args[2]);
    boolean sneak = args.length > 3 && args[3].equals("sneak");
    boolean was = player.isSneaking();
    if (sneak) {
      player.setSneaking(true);
    }
    org.bukkit.event.player.PlayerItemHeldEvent event =
        new org.bukkit.event.player.PlayerItemHeldEvent(
            player, player.getInventory().getHeldItemSlot(), slot);
    plugin.getServer().getPluginManager().callEvent(event);
    if (sneak) {
      player.setSneaking(was);
    }
    if (!event.isCancelled()) {
      player.getInventory().setHeldItemSlot(slot);
    }
    sender.sendMessage(
        "Scrolled "
            + args[1]
            + " to slot "
            + slot
            + ": "
            + (event.isCancelled() ? "refused" : "done"));
  }

  /** Presses swap-hands as the player, firing PlayerSwapHandItemsEvent; swaps unless cancelled. */
  private void swapHands(CommandSender sender, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[1]);
    if (player == null) {
      sender.sendMessage("Can't swap hands for " + args[1]);
      return;
    }
    ItemStack main = player.getInventory().getItemInMainHand();
    ItemStack off = player.getInventory().getItemInOffHand();
    org.bukkit.event.player.PlayerSwapHandItemsEvent event =
        new org.bukkit.event.player.PlayerSwapHandItemsEvent(player, off, main);
    plugin.getServer().getPluginManager().callEvent(event);
    if (!event.isCancelled()) {
      player.getInventory().setItemInMainHand(event.getMainHandItem());
      player.getInventory().setItemInOffHand(event.getOffHandItem());
    }
    sender.sendMessage(
        "Swapped hands for " + args[1] + ": " + (event.isCancelled() ? "refused" : "done"));
  }

  private void permit(CommandSender sender, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[1]);
    if (player == null) {
      sender.sendMessage("Can't permit " + args[1]);
      return;
    }
    player.addAttachment(plugin, args[2], true);
    sender.sendMessage("Permitted " + args[1] + " " + args[2]);
  }

  private void actAt(CommandSender sender, ScenarioHooks hooks, String[] args) {
    CrashPoint point = CrashPoint.valueOf(args[1].toUpperCase(Locale.ROOT).replace('-', '_'));
    String name = args[2];
    String what = args[3];
    boolean crash = args.length > 4 && args[4].equals("crash");
    hooks.armAction(
        point,
        id(name),
        () ->
            plugin
                .getServer()
                .getGlobalRegionScheduler()
                .execute(
                    plugin,
                    () -> {
                      Player player = plugin.getServer().getPlayerExact(name);
                      if (player != null) {
                        if (what.equals("empty")) {
                          player.getInventory().clear();
                          player.getEnderChest().clear();
                        } else {
                          player.getInventory().addItem(ItemStack.of(Material.STONE));
                        }
                        player.saveData();
                      }
                      if (crash) {
                        hooks.haltNow();
                      }
                    }));
    sender.sendMessage("Armed " + what + " at " + point + " for " + name);
  }

  private void workstation(CommandSender sender, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[1]);
    Material material = Material.matchMaterial(args[3]);
    if (player == null || material == null || !material.isItem()) {
      sender.sendMessage("Can't open a workstation for " + args[1]);
      return;
    }
    int count = Integer.parseInt(args[4]);
    InventoryView view =
        switch (args[2]) {
          case "anvil" ->
              MenuType.ANVIL
                  .builder()
                  .location(java.util.Objects.requireNonNull(player.getLocation()))
                  .checkReachable(false)
                  .build(player);
          case "stonecutter" ->
              MenuType.STONECUTTER
                  .builder()
                  .location(java.util.Objects.requireNonNull(player.getLocation()))
                  .checkReachable(false)
                  .build(player);
          case "merchant" ->
              MenuType.MERCHANT
                  .builder()
                  .merchant(plugin.getServer().createMerchant())
                  .checkReachable(false)
                  .build(player);
          default -> null;
        };
    if (view == null) {
      sender.sendMessage("Can't open a " + args[2] + " for " + args[1]);
      return;
    }
    player.openInventory(view);
    int inputs = args[2].equals("stonecutter") ? 1 : 2;
    for (int slot = 0; slot < inputs; slot++) {
      view.getTopInventory().setItem(slot, ItemStack.of(material, count));
    }
    sender.sendMessage("Opened a " + args[2] + " for " + args[1] + " with " + inputs + " input(s)");
  }

  private void tagged(CommandSender sender, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[1]);
    if (player == null) {
      sender.sendMessage("Can't give " + args[1] + " a tagged copy");
      return;
    }
    NamespacedKey archived =
        java.util.Objects.requireNonNull(NamespacedKey.fromString("seasons:archived"));
    if (args[2].equals("shulker")) {
      ItemStack box = ItemStack.of(Material.SHULKER_BOX);
      box.editMeta(
          org.bukkit.inventory.meta.BlockStateMeta.class,
          meta -> {
            org.bukkit.block.ShulkerBox state = (org.bukkit.block.ShulkerBox) meta.getBlockState();
            state.getInventory().addItem(ItemStack.of(Material.DIAMOND, 64));
            meta.setBlockState(state);
          });
      box.editPersistentDataContainer(
          data -> data.set(archived, org.bukkit.persistence.PersistentDataType.BOOLEAN, true));
      player.getInventory().setItemInMainHand(box);
      sender.sendMessage("Gave " + args[1] + " a tagged shulker box");
      return;
    }
    ItemStack diamond = ItemStack.of(Material.DIAMOND);
    diamond.editPersistentDataContainer(
        data -> data.set(archived, org.bukkit.persistence.PersistentDataType.BOOLEAN, true));
    ItemStack bundle = ItemStack.of(Material.BUNDLE);
    bundle.editMeta(
        org.bukkit.inventory.meta.BundleMeta.class,
        meta -> {
          meta.addItem(diamond);
          meta.addItem(ItemStack.of(Material.STICK));
        });
    player.getInventory().addItem(bundle);
    sender.sendMessage("Gave " + args[1] + " a bundle holding a tagged copy");
  }

  private void cursor(CommandSender sender, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[1]);
    Material material = Material.matchMaterial(args[2]);
    if (player == null || material == null || !material.isItem()) {
      sender.sendMessage("Can't put " + args[2] + " on the cursor of " + args[1]);
      return;
    }
    player.setItemOnCursor(ItemStack.of(material, Integer.parseInt(args[3])));
    sender.sendMessage("Put " + args[3] + " × " + args[2] + " on the cursor of " + args[1]);
  }

  private void vaultReturn(CommandSender sender, ScenarioHooks hooks, String[] args) {
    Material material = Material.matchMaterial(args[2]);
    if (material == null || !material.isItem()) {
      sender.sendMessage("Can't return " + args[2]);
      return;
    }
    int count = Integer.parseInt(args[3]);
    ItemStack stack = ItemStack.of(material, count);
    var _ =
        hooks
            .returnToVault(id(args[1]), new NeutralItem(stack.serializeAsBytes()))
            .whenComplete((done, failure) ->
                plugin
                    .getServer()
                    .getGlobalRegionScheduler()
                    .execute(
                        plugin,
                        () ->
                            sender.sendMessage(
                                failure != null
                                    ? "Returning failed: " + failure
                                    : "Returned "
                                        + count
                                        + " × "
                                        + material.getKey()
                                        + " to "
                                        + args[1]
                                        + "'s vault")));
  }

  /** The one entity with a scoreboard tag, found as a command's selector finds it. */
  private Optional<Entity> tagged(String tag) {
    return plugin
        .getServer()
        .selectEntities(plugin.getServer().getConsoleSender(), "@e[tag=" + tag + ",limit=1]")
        .stream()
        .findFirst();
  }

  /**
   * Sets a player's statistic as if they had earned it: a General-tab one by the game's id, such as
   * {@code minecraft:deaths} or {@code minecraft:play_time}, or a block mined or used, such as
   * {@code minecraft:mined:minecraft:diamond_ore}.
   */
  private void stat(CommandSender sender, String[] args) {
    Player player = plugin.getServer().getPlayerExact(args[1]);
    String id = args[2];
    int value = Integer.parseInt(args[3]);
    if (player == null) {
      sender.sendMessage(args[1] + " isn't online");
      return;
    }
    String[] parts = id.split(":", 3);
    if (parts.length == 3 && (parts[1].equals("mined") || parts[1].equals("used"))) {
      Material material = Material.matchMaterial(parts[2]);
      if (material == null) {
        sender.sendMessage("No such block " + parts[2]);
        return;
      }
      player.setStatistic(
          parts[1].equals("mined") ? Statistic.MINE_BLOCK : Statistic.USE_ITEM, material, value);
    } else {
      String key = id.equals("minecraft:play_time") ? "minecraft:play_one_minute" : id;
      Optional<Statistic> statistic =
          Arrays.stream(Statistic.values())
              .filter(s -> s.getType() == Statistic.Type.UNTYPED)
              .filter(s -> s.getKey().asString().equals(key))
              .findFirst();
      if (statistic.isEmpty()) {
        sender.sendMessage("No such statistic " + id);
        return;
      }
      player.setStatistic(statistic.get(), value);
    }
    sender.sendMessage("Set " + id + " of " + args[1] + " to " + value + ".");
  }

  private void tame(CommandSender sender, String[] args) {
    Optional<Entity> found = tagged(args[1]);
    Player owner = plugin.getServer().getPlayerExact(args[2]);
    if (found.isEmpty() || !(found.get() instanceof Tameable animal) || owner == null) {
      sender.sendMessage(
          "Can't tame "
              + args[1]
              + " to "
              + args[2]
              + ": "
              + (found.isEmpty()
                  ? "no such entity"
                  : owner == null ? "owner offline" : "untameable"));
      return;
    }
    animal.setTamed(true);
    animal.setOwner(owner);
    sender.sendMessage("Tamed " + args[1] + " to " + args[2]);
  }

  private void chest(CommandSender sender, String[] args) {
    Optional<Entity> found = tagged(args[1]);
    if (found.isEmpty() || !(found.get() instanceof ChestedHorse animal)) {
      sender.sendMessage("Can't put a chest on " + args[1]);
      return;
    }
    boolean on = args[2].equals("on");
    animal.setCarryingChest(on);
    sender.sendMessage((on ? "Put a chest on " : "Took the chest off ") + args[1]);
  }

  private void leash(CommandSender sender, String[] args) {
    Optional<Entity> found = tagged(args[1]);
    Optional<Entity> holder = tagged(args[2]);
    if (found.isEmpty()
        || !(found.get() instanceof LivingEntity animal)
        || holder.isEmpty()
        || !animal.setLeashHolder(holder.get())) {
      sender.sendMessage("Can't leash " + args[1] + " to " + args[2]);
      return;
    }
    sender.sendMessage("Leashed " + args[1] + " to " + args[2]);
  }

  /**
   * Takes Seasons' mark off a registered pet, so its going is never noticed: a stand-in for a pet
   * removed while Seasons wasn't watching, such as when its chunks are regenerated.
   */
  private void forgetPet(CommandSender sender, String[] args) {
    Optional<Entity> found = tagged(args[1]);
    NamespacedKey key = NamespacedKey.fromString("seasons:pet");
    if (found.isEmpty() || key == null) {
      sender.sendMessage("Can't forget " + args[1]);
      return;
    }
    found.get().getPersistentDataContainer().remove(key);
    sender.sendMessage("Forgot " + args[1]);
  }

  /** {@code +30s}, {@code +10m}, {@code +2h}, {@code +7d} or {@code +1500ms}. */
  private static Duration duration(String text) {
    var match = java.util.regex.Pattern.compile("\\+?(\\d+)(ms|s|m|h|d)").matcher(text);
    if (!match.matches()) {
      throw new IllegalArgumentException("Not a duration: " + text);
    }
    long amount = Long.parseLong(match.group(1));
    return switch (match.group(2)) {
      case "ms" -> Duration.ofMillis(amount);
      case "s" -> Duration.ofSeconds(amount);
      case "m" -> Duration.ofMinutes(amount);
      case "h" -> Duration.ofHours(amount);
      default -> Duration.ofDays(amount);
    };
  }

  private void sync(CommandSender sender, String[] args) {
    String id = args[1];
    int ticks = args.length > 2 && args[2].matches("\\d+") ? Integer.parseInt(args[2]) : 1;
    List<String> names =
        Arrays.asList(args)
            .subList(args.length > 2 && args[2].matches("\\d+") ? 3 : 2, args.length);
    plugin
        .getServer()
        .getGlobalRegionScheduler()
        .runDelayed(
            plugin,
            task -> {
              sender.sendMessage("Synced " + id);
              for (String name : names) {
                Player player = plugin.getServer().getPlayerExact(name);
                if (player != null && !player.equals(sender)) {
                  player.sendMessage("Synced " + id);
                }
              }
            },
            Math.max(1, ticks));
  }

  /** The position at three arguments from {@code from}, as given. */
  private static Location at(World world, String[] args, int from) {
    return new Location(
        world,
        Double.parseDouble(args[from]),
        Double.parseDouble(args[from + 1]),
        Double.parseDouble(args[from + 2]));
  }

  private @org.jspecify.annotations.Nullable World dimension(String id) {
    NamespacedKey key = NamespacedKey.fromString(id);
    return key == null ? null : plugin.getServer().getWorld(key);
  }

  /** The arguments with the type at index 2 dropped, so x y z are at 2 to 4 again. */
  private static String[] shifted(String[] args) {
    String[] rest = new String[args.length - 1];
    rest[0] = args[0];
    rest[1] = args[1];
    System.arraycopy(args, 3, rest, 2, args.length - 3);
    return rest;
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
