// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.TemplateScan;
import com.petepeterepeat.seasons.core.port.Templates;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.BrushableBlock;
import org.bukkit.block.Campfire;
import org.bukkit.block.ChiseledBookshelf;
import org.bukkit.block.Container;
import org.bukkit.block.DecoratedPot;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Lectern;
import org.bukkit.block.Structure;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.block.structure.UsageMode;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.OminousItemSpawner;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.Lootable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.structure.Palette;
import org.bukkit.structure.StructureManager;
import org.bukkit.util.BlockVector;
import org.bukkit.util.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * Cell templates through the game's structures (questions 230 to 235): {@link StructureManager}
 * reads structure files and finds what a structure block saved; a structure's first palette says
 * what each block is, DATA structure blocks being Seasons' markers.
 */
final class PaperTemplates implements Templates {
  private static final String NAMESPACE = "seasons";

  /** How far around a copy its marked entities are looked for, in case one moved. */
  private static final int MARGIN = 8;

  /** How long to wait for the entities around a copy to load before giving up. */
  private static final int ENTITY_WAIT_TICKS = 200;

  private final Plugin plugin;
  private final Server server;
  private final NamespacedKey stampKey;

  /**
   * @param stampKey the mark on every entity a stamp placed: the stamp's id and the copy's index
   */
  PaperTemplates(Plugin plugin, Server server, NamespacedKey stampKey) {
    this.plugin = plugin;
    this.server = server;
    this.stampKey = stampKey;
  }

  private StructureManager structures() {
    return server.getStructureManager();
  }

  @Override
  public Optional<byte[]> saved(String name) {
    NamespacedKey key = new NamespacedKey(NAMESPACE, name);
    org.bukkit.structure.Structure structure = structures().getStructure(key);
    if (structure == null) {
      structure = structures().loadStructure(key, false);
    }
    if (structure == null) {
      return Optional.empty();
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      structures().saveStructure(out, structure);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return Optional.of(out.toByteArray());
  }

  @Override
  public List<String> savedNames() {
    return structures().getStructures().keySet().stream()
        .filter(key -> key.getNamespace().equals(NAMESPACE))
        .map(NamespacedKey::getKey)
        .toList();
  }

  private org.bukkit.structure.Structure read(byte[] structure) {
    try {
      return structures().loadStructure(new ByteArrayInputStream(structure));
    } catch (IOException | RuntimeException e) {
      throw new IllegalArgumentException(String.valueOf(e.getMessage()), e);
    }
  }

  @Override
  public TemplateScan scan(byte[] bytes) {
    org.bukkit.structure.Structure structure = read(bytes);
    BlockVector size = structure.getSize();
    List<TemplateScan.Block> blocks = new ArrayList<>();
    List<Palette> palettes = structure.getPalettes();
    if (!palettes.isEmpty()) {
      for (BlockState state : palettes.getFirst().getBlocks()) {
        boolean structureBlock = state instanceof Structure;
        Optional<String> marker =
            state instanceof Structure block && block.getUsageMode() == UsageMode.DATA
                ? Optional.of(block.getMetadata())
                : Optional.empty();
        blocks.add(
            new TemplateScan.Block(
                state.getX(),
                state.getY(),
                state.getZ(),
                state.getType().getKey().asString(),
                items(state),
                marker,
                structureBlock));
      }
    }
    List<TemplateScan.Entity> entities = new ArrayList<>();
    for (Entity entity : structure.getEntities()) {
      entities.add(new TemplateScan.Entity(entity.getType().getKey().asString(), items(entity)));
    }
    return new TemplateScan(size.getBlockX(), size.getBlockY(), size.getBlockZ(), blocks, entities);
  }

  /**
   * How many items a block in a structure holds, whatever kind of holder it is. A loot table counts
   * as holding items: it fills the block when it is first opened or brushed (questions 235).
   */
  private static int items(BlockState state) {
    try {
      if (state instanceof Lootable lootable && lootable.getLootTable() != null) {
        return 1;
      }
      return switch (state) {
        case Container container -> count(container.getSnapshotInventory());
        case Lectern lectern -> count(lectern.getSnapshotInventory());
        case ChiseledBookshelf shelf -> count(shelf.getSnapshotInventory());
        case DecoratedPot pot -> count(pot.getSnapshotInventory());
        case Jukebox jukebox -> jukebox.hasRecord() ? 1 : 0;
        case Campfire campfire -> {
          int held = 0;
          for (int i = 0; i < campfire.getSize(); i++) {
            held += present(campfire.getItem(i)) ? 1 : 0;
          }
          yield held;
        }
        case BrushableBlock brushable -> present(brushable.getItem()) ? 1 : 0;
        default -> 0;
      };
    } catch (RuntimeException e) {
      // A holder that can't say what it holds is refused, as one holding something would be.
      return 1;
    }
  }

  /**
   * How many items an entity in a structure carries: worn, held, framed, inside it, or the entity
   * itself an item, an arrow or trident that can be picked up, or an item spawner. A loot table
   * counts as items, except a mob's, which is what it drops when killed, as a block drops itself
   * when broken: the Hall's rules stop both.
   */
  private static int items(Entity entity) {
    int held = 0;
    if (entity instanceof Item item && present(item.getItemStack())) {
      held++;
    }
    if (entity instanceof AbstractArrow arrow && present(arrow.getItemStack())) {
      held++;
    }
    if (entity instanceof OminousItemSpawner spawner && present(spawner.getItem())) {
      held++;
    }
    if (!(entity instanceof Mob)
        && entity instanceof Lootable lootable
        && lootable.getLootTable() != null) {
      held++;
    }
    if (entity instanceof ItemFrame frame && present(frame.getItem())) {
      held++;
    }
    if (entity instanceof LivingEntity living) {
      EntityEquipment equipment = living.getEquipment();
      if (equipment != null) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
          if (living.canUseEquipmentSlot(slot) && present(equipment.getItem(slot))) {
            held++;
          }
        }
      }
    }
    if (entity instanceof InventoryHolder holder) {
      held += count(holder.getInventory());
    }
    return held;
  }

  private static int count(Inventory inventory) {
    int held = 0;
    for (ItemStack item : inventory.getContents()) {
      held += present(item) ? 1 : 0;
    }
    return held;
  }

  private static boolean present(@Nullable ItemStack item) {
    return item != null && !item.isEmpty();
  }

  @Override
  public CompletableFuture<Void> place(
      byte[] bytes, Position origin, int rotation, Position min, Position max, String tag) {
    org.bukkit.structure.Structure structure = read(bytes);
    World world =
        PaperPositions.world(origin.dimension())
            .orElseThrow(() -> new IllegalArgumentException("No dimension " + origin.dimension()));
    Location at =
        PaperPositions.toLocation(
            Position.of(origin.dimension(), origin.blockX(), origin.blockY(), origin.blockZ()));
    BoundingBox around =
        new BoundingBox(
                min.blockX(),
                min.blockY(),
                min.blockZ(),
                max.blockX() + 1,
                max.blockY() + 1,
                max.blockZ() + 1)
            .expand(MARGIN);
    List<CompletableFuture<Chunk>> loading = new ArrayList<>();
    for (int cx = (int) Math.floor(around.getMinX()) >> 4;
        cx <= (int) Math.floor(around.getMaxX()) >> 4;
        cx++) {
      for (int cz = (int) Math.floor(around.getMinZ()) >> 4;
          cz <= (int) Math.floor(around.getMaxZ()) >> 4;
          cz++) {
        loading.add(world.getChunkAtAsync(cx, cz, true));
      }
    }
    CompletableFuture<Void> placed = new CompletableFuture<>();
    var _ =
        CompletableFuture.allOf(loading.toArray(CompletableFuture[]::new))
            .whenComplete((done, failure) -> {
              if (failure != null) {
                placed.completeExceptionally(failure);
                return;
              }
              List<Chunk> chunks = loading.stream().map(CompletableFuture::join).toList();
              server
                  .getGlobalRegionScheduler()
                  .execute(
                      plugin,
                      () -> {
                        // A ticket keeps each chunk loaded far enough that its entities load too.
                        chunks.forEach(chunk -> chunk.addPluginChunkTicket(plugin));
                        whenEntitiesLoaded(
                            chunks,
                            0,
                            loaded -> {
                              try {
                                if (!loaded) {
                                  throw new IllegalStateException(
                                      "the entities around copy " + tag + " didn't load");
                                }
                                placeMarked(world, structure, at, rotation, around, tag);
                                placed.complete(null);
                              } catch (RuntimeException e) {
                                placed.completeExceptionally(e);
                              } finally {
                                chunks.forEach(chunk -> chunk.removePluginChunkTicket(plugin));
                              }
                            });
                      });
            });
    return placed;
  }

  /**
   * In one tick: removes what an earlier placement of this copy left, places it, and marks the
   * entities it added, so no world save can fall between them.
   */
  private void placeMarked(
      World world,
      org.bukkit.structure.Structure structure,
      Location at,
      int rotation,
      BoundingBox around,
      String tag) {
    for (Entity earlier :
        world.getNearbyEntities(
            around,
            e ->
                tag.equals(
                    e.getPersistentDataContainer().get(stampKey, PersistentDataType.STRING)))) {
      earlier.remove();
    }
    Set<UUID> before = new HashSet<>();
    for (Entity entity : world.getNearbyEntities(around)) {
      before.add(entity.getUniqueId());
    }
    structure.place(at, true, rotation(rotation), Mirror.NONE, 0, 1.0f, new Random());
    for (Entity entity : world.getNearbyEntities(around)) {
      if (!before.contains(entity.getUniqueId())) {
        entity.getPersistentDataContainer().set(stampKey, PersistentDataType.STRING, tag);
      }
    }
  }

  private void whenEntitiesLoaded(
      List<Chunk> chunks, int waited, java.util.function.Consumer<Boolean> then) {
    if (chunks.stream().allMatch(Chunk::isEntitiesLoaded)) {
      then.accept(true);
      return;
    }
    if (waited >= ENTITY_WAIT_TICKS) {
      then.accept(false);
      return;
    }
    server
        .getGlobalRegionScheduler()
        .runDelayed(plugin, task -> whenEntitiesLoaded(chunks, waited + 1, then), 1);
  }

  private static StructureRotation rotation(int degrees) {
    return switch (degrees) {
      case 90 -> StructureRotation.CLOCKWISE_90;
      case 180 -> StructureRotation.CLOCKWISE_180;
      case 270 -> StructureRotation.COUNTERCLOCKWISE_90;
      default -> StructureRotation.NONE;
    };
  }
}
