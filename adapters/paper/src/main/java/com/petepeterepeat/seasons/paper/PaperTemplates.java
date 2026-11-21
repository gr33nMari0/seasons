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
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
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
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.structure.Palette;
import org.bukkit.structure.StructureManager;
import org.bukkit.util.BlockVector;
import org.jspecify.annotations.Nullable;

/**
 * Cell templates through the game's structures (questions 230 to 235): {@link StructureManager}
 * reads structure files and finds what a structure block saved; a structure's first palette says
 * what each block is, DATA structure blocks being Seasons' markers.
 */
final class PaperTemplates implements Templates {
  private static final String NAMESPACE = "seasons";

  private final Server server;

  PaperTemplates(Server server) {
    this.server = server;
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

  /** How many items a block in a structure holds, whatever kind of holder it is. */
  private static int items(BlockState state) {
    try {
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

  /** How many items an entity in a structure carries: worn, held, framed or inside it. */
  private static int items(Entity entity) {
    int held = 0;
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
  public void place(byte[] bytes, Position origin, int rotation) {
    org.bukkit.structure.Structure structure = read(bytes);
    Location at =
        PaperPositions.toLocation(
            Position.of(origin.dimension(), origin.blockX(), origin.blockY(), origin.blockZ()));
    structure.place(at, true, rotation(rotation), Mirror.NONE, 0, 1.0f, new Random());
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
