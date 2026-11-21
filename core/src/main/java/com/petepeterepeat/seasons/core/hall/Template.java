// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.TemplateScan;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A cell template (questions 230 to 235): a structure file, where it came from, what it holds, and
 * its Seasons markers.
 */
public final class Template {
  /** The metadata prefix of a Seasons marker. */
  public static final String PREFIX = "seasons:";

  /** Blocks the Hall doesn't allow (A23, questions 235), which no template may hold. */
  static final Set<String> NOT_ALLOWED =
      Set.of(
          "minecraft:hopper",
          "minecraft:dropper",
          "minecraft:dispenser",
          "minecraft:crafter",
          "minecraft:ender_chest",
          "minecraft:lava",
          // Each makes items or mobs from nothing (questions 235).
          "minecraft:spawner",
          "minecraft:trial_spawner",
          "minecraft:vault");

  /** Entities the Hall doesn't allow. */
  static final Set<String> ENTITIES_NOT_ALLOWED =
      Set.of("minecraft:hopper_minecart", "minecraft:spawner_minecart");

  /** Where a template came from, in the order they are looked for. */
  public enum Source {
    /** {@code <world>/seasons/templates/<name>.nbt}. */
    FILE("hof.stamp.source-file"),
    /** A structure saved by a structure block as {@code seasons:<name>}. */
    STRUCTURE("hof.stamp.source-structure"),
    /** The jar's defaults. */
    BUNDLED("hof.stamp.source-bundled");

    private final String message;

    Source(String message) {
      this.message = message;
    }

    /** The messages.yml key of how the stamp's reply names it. */
    public String message() {
      return message;
    }
  }

  /**
   * One marker as an anchor: its type and the block it anchors, relative to the template's minimum
   * corner. An archive marker anchors the block below it (questions 231).
   *
   * @param markerX the marker's own block, which becomes air
   */
  public record Marker(
      AnchorType type, int x, int y, int z, int markerX, int markerY, int markerZ) {}

  private final String name;
  private final Source source;
  private final byte[] data;
  private final TemplateScan scan;

  /**
   * @param data the structure file's bytes, which the adapter places
   */
  public Template(String name, Source source, byte[] data, TemplateScan scan) {
    this.name = name;
    this.source = source;
    this.data = data.clone();
    this.scan = scan;
  }

  public String name() {
    return name;
  }

  public Source source() {
    return source;
  }

  public byte[] data() {
    return data.clone();
  }

  public TemplateScan scan() {
    return scan;
  }

  /** The markers that name an anchor type, in the order the structure lists them. */
  public List<Marker> markers() {
    List<Marker> out = new ArrayList<>();
    for (TemplateScan.Block block : scan.blocks()) {
      Optional<AnchorType> type = block.marker().flatMap(Template::type);
      if (type.isPresent()) {
        int y = type.get() == AnchorType.ARCHIVE ? block.y() - 1 : block.y();
        out.add(new Marker(type.get(), block.x(), y, block.z(), block.x(), block.y(), block.z()));
      }
    }
    return out;
  }

  /** Why the template can't be stamped into a gallery of a kind, as text, or empty if it can. */
  public Optional<String> problem(Gallery.Kind kind, Messages messages) {
    Map<String, TemplateScan.Block> at = new HashMap<>();
    for (TemplateScan.Block block : scan.blocks()) {
      at.put(key(block.x(), block.y(), block.z()), block);
    }
    for (TemplateScan.Block block : scan.blocks()) {
      if (block.structureBlock()) {
        if (block.marker().isEmpty() || !block.marker().get().startsWith(PREFIX)) {
          return Optional.of(messages.text("hof.stamp.reasons.not-a-marker", xyz(block)));
        }
        if (type(block.marker().get()).isEmpty()) {
          return Optional.of(
              messages.text(
                  "hof.stamp.reasons.unknown-marker",
                  with(xyz(block), "marker", block.marker().get())));
        }
      }
    }
    for (TemplateScan.Block block : scan.blocks()) {
      if (NOT_ALLOWED.contains(block.type())) {
        return Optional.of(
            messages.text(
                "hof.stamp.reasons.not-allowed", with(xyz(block), "block", block.type())));
      }
    }
    for (TemplateScan.Block block : scan.blocks()) {
      if (block.items() > 0) {
        return Optional.of(
            messages.text(
                "hof.stamp.reasons.holds-items", with(xyz(block), "block", block.type())));
      }
    }
    for (TemplateScan.Entity entity : scan.entities()) {
      if (ENTITIES_NOT_ALLOWED.contains(entity.type())) {
        return Optional.of(
            messages.text("hof.stamp.reasons.entity-not-allowed", "entity", entity.type()));
      }
      if (entity.items() > 0) {
        return Optional.of(
            messages.text("hof.stamp.reasons.entity-items", "entity", entity.type()));
      }
    }
    Map<AnchorType, Integer> counts = new EnumMap<>(AnchorType.class);
    for (Marker marker : markers()) {
      counts.merge(marker.type(), 1, Integer::sum);
    }
    int figures = counts.getOrDefault(AnchorType.FIGURE, 0);
    if (figures == 0 && kind == Gallery.Kind.SEASON) {
      return Optional.of(messages.text("hof.stamp.reasons.no-figure"));
    }
    if (figures > 1) {
      return Optional.of(
          messages.text("hof.stamp.reasons.figures", "count", Integer.toString(figures)));
    }
    for (AnchorType type : List.of(AnchorType.PLAQUE, AnchorType.ENTRY)) {
      int count = counts.getOrDefault(type, 0);
      if (count > 1) {
        return Optional.of(
            messages.text(
                "hof.stamp.reasons.too-many", "count", Integer.toString(count), "type", type.id()));
      }
    }
    int gates = counts.getOrDefault(AnchorType.GATE, 0);
    if (gates != 0 && gates != 2) {
      return Optional.of(
          messages.text(
              "hof.stamp.reasons.gate",
              "count",
              Integer.toString(gates),
              "s",
              gates == 1 ? "" : "s"));
    }
    for (Marker marker : markers()) {
      if (marker.type() == AnchorType.ARCHIVE) {
        TemplateScan.Block below = at.get(key(marker.x(), marker.y(), marker.z()));
        if (below == null || isAir(below.type()) || below.structureBlock()) {
          return Optional.of(
              messages.text(
                  "hof.stamp.reasons.floating-archive",
                  "x",
                  Integer.toString(marker.markerX()),
                  "y",
                  Integer.toString(marker.markerY()),
                  "z",
                  Integer.toString(marker.markerZ())));
        }
      }
    }
    return Optional.empty();
  }

  /** The anchor type a marker's metadata names, such as {@code seasons:figure}. */
  static Optional<AnchorType> type(String metadata) {
    if (!metadata.startsWith(PREFIX)) {
      return Optional.empty();
    }
    String id = metadata.substring(PREFIX.length());
    for (AnchorType type : AnchorType.values()) {
      if (type.id().equals(id)) {
        return Optional.of(type);
      }
    }
    return Optional.empty();
  }

  static boolean isAir(String type) {
    return type.equals("minecraft:air")
        || type.equals("minecraft:cave_air")
        || type.equals("minecraft:void_air");
  }

  private static String key(int x, int y, int z) {
    return x + "," + y + "," + z;
  }

  private static String[] xyz(TemplateScan.Block block) {
    return new String[] {
      "x",
      Integer.toString(block.x()),
      "y",
      Integer.toString(block.y()),
      "z",
      Integer.toString(block.z())
    };
  }

  private static String[] with(String[] placeholders, String key, String value) {
    String[] all = new String[placeholders.length + 2];
    System.arraycopy(placeholders, 0, all, 0, placeholders.length);
    all[placeholders.length] = key;
    all[placeholders.length + 1] = value;
    return all;
  }
}
