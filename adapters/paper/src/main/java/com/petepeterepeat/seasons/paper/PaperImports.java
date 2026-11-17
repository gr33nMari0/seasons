// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.port.Imports;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.Skin;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Rotation;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.EulerAngle;

/**
 * Reads and changes hand-built Season 1 cells for {@code /hof import}. Every change goes straight
 * to the live block or entity, on the server thread, and nothing is ever dropped.
 */
final class PaperImports implements Imports {
  private static final List<EquipmentSlot> SLOTS =
      List.of(
          EquipmentSlot.HEAD,
          EquipmentSlot.CHEST,
          EquipmentSlot.LEGS,
          EquipmentSlot.FEET,
          EquipmentSlot.HAND,
          EquipmentSlot.OFF_HAND);
  private static final List<String> POSES =
      List.of("head", "body", "leftArm", "rightArm", "leftLeg", "rightLeg");

  /** How many ticks a scan waits for a chunk's entities to load. */
  private static final int ENTITY_WAIT_TICKS = 200;

  private final Plugin plugin;
  private final Server server;
  private final PaperItemCodec codec;

  PaperImports(Plugin plugin, Server server, PaperItemCodec codec) {
    this.plugin = plugin;
    this.server = server;
    this.codec = codec;
  }

  @Override
  public CompletableFuture<Scan> scan(Position min, Position max) {
    World world = world(min);
    List<CompletableFuture<Chunk>> loading = new ArrayList<>();
    for (int x = min.blockX() >> 4; x <= max.blockX() >> 4; x++) {
      for (int z = min.blockZ() >> 4; z <= max.blockZ() >> 4; z++) {
        loading.add(world.getChunkAtAsync(x, z, true));
      }
    }
    CompletableFuture<Scan> scanned = new CompletableFuture<>();
    var _ =
        CompletableFuture.allOf(loading.toArray(CompletableFuture[]::new))
            .whenComplete((done, failure) -> {
              if (failure != null) {
                scanned.completeExceptionally(failure);
                return;
              }
              List<Chunk> chunks = loading.stream().map(CompletableFuture::join).toList();
              // A ticket keeps each chunk loaded far enough that its entities load too.
              server
                  .getGlobalRegionScheduler()
                  .execute(
                      plugin,
                      () -> {
                        chunks.forEach(chunk -> chunk.addPluginChunkTicket(plugin));
                        whenEntitiesLoaded(
                            chunks,
                            0,
                            () -> {
                              try {
                                scanned.complete(read(chunks, min, max));
                              } catch (RuntimeException e) {
                                scanned.completeExceptionally(e);
                              } finally {
                                chunks.forEach(chunk -> chunk.removePluginChunkTicket(plugin));
                              }
                            });
                      });
            });
    return scanned;
  }

  private void whenEntitiesLoaded(List<Chunk> chunks, int waited, Runnable then) {
    if (waited >= ENTITY_WAIT_TICKS || chunks.stream().allMatch(Chunk::isEntitiesLoaded)) {
      then.run();
      return;
    }
    server
        .getGlobalRegionScheduler()
        .runDelayed(plugin, task -> whenEntitiesLoaded(chunks, waited + 1, then), 1);
  }

  private Scan read(List<Chunk> chunks, Position min, Position max) {
    BoundingBox box =
        new BoundingBox(
            min.blockX(),
            min.blockY(),
            min.blockZ(),
            max.blockX() + 1,
            max.blockY() + 1,
            max.blockZ() + 1);
    List<Imports.Container> containers = new ArrayList<>();
    List<Stand> stands = new ArrayList<>();
    List<Frame> frames = new ArrayList<>();
    List<String> signs = new ArrayList<>();
    List<Other> others = new ArrayList<>();
    Set<Location> seen = new HashSet<>();
    for (Chunk chunk : chunks) {
      for (BlockState state : chunk.getTileEntities()) {
        Location at = state.getLocation();
        if (!box.contains(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5)) {
          continue;
        }
        switch (state) {
          case Chest chest when chest.getInventory() instanceof DoubleChestInventory both -> {
            // A double chest is one container, at the half its slots start in.
            Location left = both.getLeftSide().getLocation();
            if (left != null && seen.add(left.toBlockLocation())) {
              containers.add(container(left.getBlock(), both));
            }
          }
          case Chest chest -> containers.add(container(state.getBlock(), chest.getInventory()));
          case Barrel barrel -> containers.add(container(state.getBlock(), barrel.getInventory()));
          case ShulkerBox shulker ->
              containers.add(container(state.getBlock(), shulker.getInventory()));
          case org.bukkit.block.Container other -> {
            int items = 0;
            for (ItemStack stack : other.getInventory().getContents()) {
              items += stack == null ? 0 : stack.getAmount();
            }
            if (items > 0) {
              others.add(new Other(position(at), state.getType().getKey().asString(), items));
            }
          }
          case Sign sign ->
              signs.add(
                  sign.getSide(Side.FRONT).lines().stream()
                      .map(PlainTextComponentSerializer.plainText()::serialize)
                      .filter(line -> !line.isBlank())
                      .collect(Collectors.joining("\n")));
          default -> {}
        }
      }
      for (Entity entity : chunk.getEntities()) {
        if (!box.contains(entity.getLocation().toVector())) {
          continue;
        }
        if (entity instanceof ArmorStand stand) {
          stands.add(stand(stand));
        } else if (entity instanceof ItemFrame frame) {
          frames.add(
              new Frame(
                  frame.getUniqueId(),
                  PaperPositions.toPosition(frame.getLocation()),
                  codec.encode(frame.getItem()),
                  frameProperties(frame)));
        }
      }
    }
    signs.removeIf(String::isBlank);
    return new Scan(containers, stands, frames, signs, others);
  }

  private Imports.Container container(Block block, Inventory inventory) {
    List<Optional<NeutralItem>> slots = new ArrayList<>();
    for (ItemStack stack : inventory.getContents()) {
      slots.add(stack == null ? Optional.empty() : codec.encode(stack));
    }
    return new Imports.Container(
        position(block.getLocation()), block.getType().getKey().asString(), slots);
  }

  private Stand stand(ArmorStand stand) {
    EntityEquipment equipment = stand.getEquipment();
    List<Optional<NeutralItem>> worn = new ArrayList<>();
    List<Head> heads = new ArrayList<>();
    for (EquipmentSlot slot : SLOTS) {
      ItemStack item = equipment.getItem(slot);
      worn.add(codec.encode(item));
      head(item).ifPresent(heads::add);
    }
    Map<String, Object> properties = new HashMap<>();
    properties.put("arms", flag(stand.hasArms()));
    properties.put("small", flag(stand.isSmall()));
    properties.put("basePlate", flag(stand.hasBasePlate()));
    properties.put("visible", flag(stand.isVisible()));
    properties.put("marker", flag(stand.isMarker()));
    properties.put("gravity", flag(stand.hasGravity()));
    List<EulerAngle> poses =
        List.of(
            stand.getHeadPose(),
            stand.getBodyPose(),
            stand.getLeftArmPose(),
            stand.getRightArmPose(),
            stand.getLeftLegPose(),
            stand.getRightLegPose());
    for (int i = 0; i < POSES.size(); i++) {
      EulerAngle pose = poses.get(i);
      properties.put(POSES.get(i) + ".x", pose.getX());
      properties.put(POSES.get(i) + ".y", pose.getY());
      properties.put(POSES.get(i) + ".z", pose.getZ());
    }
    return new Stand(
        stand.getUniqueId(),
        PaperPositions.toPosition(stand.getLocation()),
        worn,
        heads,
        properties);
  }

  private static Optional<Head> head(ItemStack item) {
    if (item.getType() != Material.PLAYER_HEAD) {
      return Optional.empty();
    }
    ResolvableProfile profile = item.getData(DataComponentTypes.PROFILE);
    if (profile == null) {
      return Optional.of(new Head(Optional.empty(), Optional.empty(), Optional.empty()));
    }
    Optional<Skin> skin =
        profile.properties().stream()
            .filter(p -> p.getName().equals("textures"))
            .findFirst()
            .map(p -> new Skin(p.getValue(), Optional.ofNullable(p.getSignature())));
    return Optional.of(
        new Head(
            Optional.ofNullable(profile.uuid()).map(PlayerId::new),
            Optional.ofNullable(profile.name()),
            skin));
  }

  private static Map<String, Object> frameProperties(ItemFrame frame) {
    Map<String, Object> properties = new HashMap<>();
    properties.put("facing", frame.getFacing().name());
    properties.put("rotation", frame.getRotation().name());
    properties.put("glowing", flag(frame instanceof GlowItemFrame));
    properties.put("fixed", flag(frame.isFixed()));
    properties.put("visible", flag(frame.isVisible()));
    return properties;
  }

  @Override
  public boolean emptyContainer(Position block) {
    if (!(at(block).getState() instanceof org.bukkit.block.Container container)) {
      return false;
    }
    container.getInventory().clear();
    return true;
  }

  @Override
  public boolean fillContainer(Position block, List<Optional<NeutralItem>> slots) {
    if (!(at(block).getState() instanceof org.bukkit.block.Container container)) {
      return false;
    }
    Inventory inventory = container.getInventory();
    if (!inventory.isEmpty() || inventory.getSize() != slots.size()) {
      return false;
    }
    ItemStack[] contents = new ItemStack[slots.size()];
    for (int i = 0; i < contents.length; i++) {
      contents[i] = codec.decode(slots.get(i));
    }
    inventory.setContents(contents);
    return true;
  }

  @Override
  public boolean remove(UUID id) {
    Entity entity = server.getEntity(id);
    if (entity == null) {
      return false;
    }
    if (entity instanceof ArmorStand stand) {
      // Emptied first, so nothing can drop whatever removes it.
      for (EquipmentSlot slot : SLOTS) {
        stand.getEquipment().setItem(slot, null);
      }
    } else if (entity instanceof ItemFrame frame) {
      frame.setItem(null, false);
    }
    entity.remove();
    return true;
  }

  @Override
  public UUID spawnStand(
      Position at, Map<String, Object> properties, List<Optional<NeutralItem>> worn) {
    Location location = PaperPositions.toLocation(at);
    ArmorStand stand =
        location
            .getWorld()
            .spawn(
                location,
                ArmorStand.class,
                spawned -> {
                  spawned.setArms(is(properties, "arms"));
                  spawned.setSmall(is(properties, "small"));
                  spawned.setBasePlate(is(properties, "basePlate"));
                  spawned.setVisible(is(properties, "visible"));
                  spawned.setMarker(is(properties, "marker"));
                  spawned.setGravity(is(properties, "gravity"));
                  spawned.setHeadPose(pose(properties, "head"));
                  spawned.setBodyPose(pose(properties, "body"));
                  spawned.setLeftArmPose(pose(properties, "leftArm"));
                  spawned.setRightArmPose(pose(properties, "rightArm"));
                  spawned.setLeftLegPose(pose(properties, "leftLeg"));
                  spawned.setRightLegPose(pose(properties, "rightLeg"));
                  for (int i = 0; i < SLOTS.size() && i < worn.size(); i++) {
                    spawned.getEquipment().setItem(SLOTS.get(i), codec.decode(worn.get(i)));
                  }
                });
    return stand.getUniqueId();
  }

  @Override
  public UUID spawnFrame(Position at, Map<String, Object> properties, Optional<NeutralItem> item) {
    Location location = PaperPositions.toLocation(at).toBlockLocation();
    Class<? extends ItemFrame> type =
        is(properties, "glowing") ? GlowItemFrame.class : ItemFrame.class;
    BlockFace facing =
        BlockFace.valueOf(String.valueOf(properties.getOrDefault("facing", "NORTH")));
    ItemFrame frame =
        location
            .getWorld()
            .spawn(
                location,
                type,
                spawned -> {
                  spawned.setFacingDirection(facing, true);
                  spawned.setItem(codec.decode(item), false);
                  spawned.setRotation(
                      Rotation.valueOf(
                          String.valueOf(properties.getOrDefault("rotation", "NONE"))));
                  spawned.setFixed(is(properties, "fixed"));
                  spawned.setVisible(
                      !properties.containsKey("visible") || is(properties, "visible"));
                });
    return frame.getUniqueId();
  }

  private static Byte flag(boolean value) {
    return value ? (byte) 1 : (byte) 0;
  }

  private static boolean is(Map<String, Object> properties, String key) {
    return properties.get(key) instanceof Number n && n.intValue() != 0;
  }

  private static EulerAngle pose(Map<String, Object> properties, String name) {
    return new EulerAngle(
        number(properties, name + ".x"),
        number(properties, name + ".y"),
        number(properties, name + ".z"));
  }

  private static double number(Map<String, Object> properties, String key) {
    return properties.get(key) instanceof Number n ? n.doubleValue() : 0;
  }

  private static Position position(Location at) {
    return PaperPositions.toPosition(at.toBlockLocation());
  }

  private static Block at(Position block) {
    return PaperPositions.toLocation(block).getBlock();
  }

  private static World world(Position at) {
    return PaperPositions.world(at.dimension())
        .orElseThrow(() -> new IllegalArgumentException("No dimension " + at.dimension()));
  }
}
