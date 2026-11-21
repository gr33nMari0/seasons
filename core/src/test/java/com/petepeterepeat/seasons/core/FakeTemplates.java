// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import com.petepeterepeat.seasons.core.hall.StampPlan;
import com.petepeterepeat.seasons.core.port.BlockInfo;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.TemplateScan;
import com.petepeterepeat.seasons.core.port.Templates;
import com.petepeterepeat.seasons.core.store.nbt.Nbt;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Structure files read with the plain NBT reader and placed into the fake world as the game places
 * them: turned about the origin, structure blocks and all, every block in the palette set.
 */
public final class FakeTemplates implements Templates {
  private final FakePlatform fake;

  /** Structures saved by a structure block, by name after {@code seasons:}. */
  public final Map<String, byte[]> saved = new HashMap<>();

  /** Each placement: the origin and the rotation. */
  public final List<Position> placed = new ArrayList<>();

  FakeTemplates(FakePlatform fake) {
    this.fake = fake;
  }

  @Override
  public Optional<byte[]> saved(String name) {
    return Optional.ofNullable(saved.get(name)).map(byte[]::clone);
  }

  @Override
  public List<String> savedNames() {
    return List.copyOf(saved.keySet());
  }

  @Override
  public TemplateScan scan(byte[] structure) {
    Map<String, Object> root;
    try {
      root = Nbt.read(structure);
    } catch (IOException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
    int[] size = ints(root.get("size"));
    List<Map<String, Object>> palette = list(root.get("palette"));
    List<TemplateScan.Block> blocks = new ArrayList<>();
    for (Map<String, Object> block : list(root.get("blocks"))) {
      int[] pos = ints(block.get("pos"));
      Map<String, Object> state = palette.get((Integer) block.get("state"));
      String name = (String) state.get("Name");
      @SuppressWarnings("unchecked")
      Map<String, Object> nbt = (Map<String, Object>) block.getOrDefault("nbt", Map.of());
      boolean structureBlock = name.equals("minecraft:structure_block");
      Optional<String> marker =
          structureBlock && "DATA".equals(nbt.get("mode"))
              ? Optional.of(String.valueOf(nbt.getOrDefault("metadata", "")))
              : Optional.empty();
      int items = nbt.containsKey("Items") ? list(nbt.get("Items")).size() : 0;
      blocks.add(
          new TemplateScan.Block(pos[0], pos[1], pos[2], name, items, marker, structureBlock));
    }
    List<TemplateScan.Entity> entities = new ArrayList<>();
    for (Map<String, Object> entity : list(root.getOrDefault("entities", emptyList()))) {
      @SuppressWarnings("unchecked")
      Map<String, Object> nbt = (Map<String, Object>) entity.getOrDefault("nbt", Map.of());
      int items = nbt.containsKey("Item") ? 1 : 0;
      entities.add(new TemplateScan.Entity(String.valueOf(nbt.get("id")), items));
    }
    return new TemplateScan(size[0], size[1], size[2], blocks, entities);
  }

  @Override
  public void place(byte[] structure, Position origin, int rotation) {
    TemplateScan scan = scan(structure);
    placed.add(new Position(origin.dimension(), origin.x(), origin.y(), origin.z(), rotation, 0f));
    for (TemplateScan.Block block : scan.blocks()) {
      int[] turned = StampPlan.turn(block.x(), block.z(), rotation);
      boolean air = block.type().endsWith(":air");
      fake.setBlock(
          origin.blockX() + turned[0],
          origin.blockY() + block.y(),
          origin.blockZ() + turned[1],
          new BlockInfo(block.type(), !air, air, false));
    }
  }

  private static Nbt.NbtList emptyList() {
    return new Nbt.NbtList(Nbt.compoundType(), List.of());
  }

  private static List<Map<String, Object>> list(Object value) {
    return ((Nbt.NbtList) value).compounds();
  }

  private static int[] ints(Object value) {
    List<Object> values = ((Nbt.NbtList) value).values();
    int[] out = new int[values.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = (Integer) values.get(i);
    }
    return out;
  }
}
