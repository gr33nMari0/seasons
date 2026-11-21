// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import com.petepeterepeat.seasons.core.store.nbt.Nbt;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes vanilla structure files (the format a structure block saves) with the plain NBT writer:
 * for the default templates and for tests. Blocks set later replace earlier ones at a position.
 */
public final class StructureBuilder {
  /** Minecraft 26.3's data version, which the default templates carry. */
  public static final int DATA_VERSION = 5023;

  private final int sizeX;
  private final int sizeY;
  private final int sizeZ;
  private final Map<String, Map<String, Object>> blocks = new TreeMap<>();
  private final List<Map<String, Object>> entities = new ArrayList<>();

  public StructureBuilder(int sizeX, int sizeY, int sizeZ) {
    this.sizeX = sizeX;
    this.sizeY = sizeY;
    this.sizeZ = sizeZ;
  }

  /** A block with no properties and no block entity. */
  public StructureBuilder block(int x, int y, int z, String name) {
    return block(x, y, z, name, Map.of(), null);
  }

  /** Every position of a box, corners included. */
  public StructureBuilder fill(int x1, int y1, int z1, int x2, int y2, int z2, String name) {
    for (int x = x1; x <= x2; x++) {
      for (int y = y1; y <= y2; y++) {
        for (int z = z1; z <= z2; z++) {
          block(x, y, z, name);
        }
      }
    }
    return this;
  }

  /** A structure block in DATA mode: a Seasons marker when its metadata is seasons:<type>. */
  public StructureBuilder marker(int x, int y, int z, String metadata) {
    Map<String, Object> nbt = new LinkedHashMap<>();
    nbt.put("id", "minecraft:structure_block");
    nbt.put("mode", "DATA");
    nbt.put("metadata", metadata);
    return block(x, y, z, "minecraft:structure_block", Map.of("mode", "data"), nbt);
  }

  /** A container with its block entity, holding {@code items} single diamonds. */
  public StructureBuilder container(int x, int y, int z, String name, int items) {
    Map<String, Object> nbt = new LinkedHashMap<>();
    nbt.put("id", name);
    List<Map<String, Object>> stacks = new ArrayList<>();
    for (int i = 0; i < items; i++) {
      Map<String, Object> stack = new LinkedHashMap<>();
      stack.put("Slot", (byte) i);
      stack.put("id", "minecraft:diamond");
      stack.put("count", 1);
      stacks.add(stack);
    }
    nbt.put("Items", Nbt.NbtList.ofCompounds(stacks));
    return block(x, y, z, name, Map.of(), nbt);
  }

  public StructureBuilder block(
      int x, int y, int z, String name, Map<String, String> properties, Map<String, Object> nbt) {
    Map<String, Object> block = new LinkedHashMap<>();
    block.put("name", name);
    block.put("properties", new TreeMap<>(properties));
    block.put("pos", List.of(x, y, z));
    if (nbt != null) {
      block.put("nbt", nbt);
    }
    blocks.put(String.format("%04d/%04d/%04d", y, z, x), block);
    return this;
  }

  /** An entity, such as an item frame holding an item. */
  public StructureBuilder entity(Map<String, Object> nbt) {
    Map<String, Object> entity = new LinkedHashMap<>();
    entity.put("pos", new Nbt.NbtList(6, List.of(0.5, 1.0, 0.5)));
    entity.put("blockPos", ints(0, 1, 0));
    entity.put("nbt", nbt);
    entities.add(entity);
    return this;
  }

  /** The structure file, gzipped as the game writes it. */
  @SuppressWarnings("unchecked")
  public byte[] build() {
    List<Map<String, Object>> palette = new ArrayList<>();
    List<String> keys = new ArrayList<>();
    List<Map<String, Object>> placed = new ArrayList<>();
    for (Map<String, Object> block : blocks.values()) {
      Map<String, String> properties = (Map<String, String>) block.get("properties");
      String key = block.get("name") + properties.toString();
      int state = keys.indexOf(key);
      if (state < 0) {
        state = keys.size();
        keys.add(key);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("Name", block.get("name"));
        if (!properties.isEmpty()) {
          entry.put("Properties", new LinkedHashMap<String, Object>(properties));
        }
        palette.add(entry);
      }
      List<Integer> pos = (List<Integer>) block.get("pos");
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("pos", ints(pos.get(0), pos.get(1), pos.get(2)));
      out.put("state", state);
      if (block.containsKey("nbt")) {
        out.put("nbt", block.get("nbt"));
      }
      placed.add(out);
    }
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("DataVersion", DATA_VERSION);
    root.put("size", ints(sizeX, sizeY, sizeZ));
    root.put("palette", Nbt.NbtList.ofCompounds(palette));
    root.put("blocks", Nbt.NbtList.ofCompounds(placed));
    root.put("entities", Nbt.NbtList.ofCompounds(entities));
    return Nbt.writeCompressed(root);
  }

  private static Nbt.NbtList ints(int x, int y, int z) {
    return new Nbt.NbtList(3, List.of(x, y, z));
  }
}
