// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.petepeterepeat.seasons.core.config.ConfigLoader;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.store.AtomicFiles;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.regex.Pattern;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Reads and writes {@code galleries/<id>.yml}. Commands and hand edits share the format, so a file
 * written by {@code /hof} reads back the same, and a hand edit takes effect with {@code /season
 * reload}.
 *
 * <pre>
 * schema-version: 1
 * id: season-2
 * kind: season
 * season: 2
 * dimension: minecraft:overworld
 * bounds: { min: [20, -61, 20], max: [44, -50, 30] }
 * entry: { pos: [21, -60, 21], yaw: 0 }
 * exits: [[21, -60, 20]]
 * cells:
 *   c1:
 *     bounds: { min: [20, -61, 22], max: [24, -55, 30] }
 *     anchors:
 *       - { type: figure, pos: [22, -60, 28], yaw: 180 }
 *       - { type: archive, pos: [23, -60, 28] }
 *     owners: { "2": 7c2a... }
 * </pre>
 */
public final class GalleryFiles {
  /** Gallery and cell ids: lower-case letters, digits, dashes and underscores. */
  public static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");

  private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

  private GalleryFiles() {}

  /** A value in a gallery file that doesn't make sense, at a key path such as {@code cells.c1}. */
  public static final class Invalid extends Exception {
    private static final long serialVersionUID = 1L;
    private final String path;

    public Invalid(String path, String message) {
      super(message);
      this.path = path;
    }

    public String path() {
      return path;
    }
  }

  /**
   * A gallery from its file's root node.
   *
   * @param id the id the file name gives
   */
  public static Gallery read(String id, ConfigurationNode node) throws Invalid {
    String declared = node.node("id").getString(id);
    if (!declared.equals(id)) {
      throw new Invalid("id", "says " + declared + " in the file for " + id);
    }
    String kindName = node.node("kind").getString("season");
    Gallery.Kind kind;
    try {
      kind = Gallery.Kind.valueOf(kindName.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new Invalid("kind", "is season or museum, not " + kindName);
    }
    int season = node.node("season").getInt(0);
    if (kind == Gallery.Kind.SEASON && season < 1) {
      throw new Invalid("season", "a season's gallery needs its season number");
    }
    String dimension = node.node("dimension").getString("minecraft:overworld");
    if (!DIMENSION.matcher(dimension).matches()) {
      throw new Invalid("dimension", "'" + dimension + "' is not a dimension id");
    }
    Optional<Bounds> bounds = bounds(node.node("bounds"), dimension, "bounds");
    Optional<Position> entry =
        node.node("entry").virtual()
            ? Optional.empty()
            : Optional.of(point(node.node("entry"), dimension, "entry"));
    List<Position> exits = new ArrayList<>();
    List<? extends ConfigurationNode> exitNodes = node.node("exits").childrenList();
    for (int i = 0; i < exitNodes.size(); i++) {
      exits.add(xyz(exitNodes.get(i), dimension, 0f, "exits." + i));
    }
    List<Cell> cells = new ArrayList<>();
    for (Map.Entry<Object, ? extends ConfigurationNode> entry2 :
        node.node("cells").childrenMap().entrySet()) {
      String cellId = String.valueOf(entry2.getKey());
      cells.add(cell(cellId, entry2.getValue(), dimension, "cells." + cellId));
    }
    return new Gallery(id, kind, season, dimension, bounds, entry, exits, cells);
  }

  private static Cell cell(String id, ConfigurationNode node, String dimension, String path)
      throws Invalid {
    if (!ID.matcher(id).matches()) {
      throw new Invalid(path, "cell ids are lower-case letters, digits, - and _");
    }
    Optional<Bounds> bounds = bounds(node.node("bounds"), dimension, path + ".bounds");
    List<Anchor> anchors = new ArrayList<>();
    List<? extends ConfigurationNode> anchorNodes = node.node("anchors").childrenList();
    for (int i = 0; i < anchorNodes.size(); i++) {
      ConfigurationNode anchor = anchorNodes.get(i);
      String at = path + ".anchors." + i;
      String typeName = anchor.node("type").getString("");
      AnchorType type =
          AnchorType.of(typeName)
              .orElseThrow(
                  () -> new Invalid(at + ".type", "unknown anchor type '" + typeName + "'"));
      Position position = point(anchor, dimension, at);
      OptionalInt page =
          anchor.node("page").virtual()
              ? OptionalInt.empty()
              : OptionalInt.of(anchor.node("page").getInt());
      anchors.add(new Anchor(type, position, page));
    }
    Map<Integer, PlayerId> owners = new HashMap<>();
    for (Map.Entry<Object, ? extends ConfigurationNode> owner :
        node.node("owners").childrenMap().entrySet()) {
      String where = path + ".owners." + owner.getKey();
      try {
        int season = Integer.parseInt(String.valueOf(owner.getKey()));
        owners.put(
            season, new PlayerId(UUID.fromString(String.valueOf(owner.getValue().getString()))));
      } catch (IllegalArgumentException e) {
        throw new Invalid(where, "owners are season: player UUID");
      }
    }
    return new Cell(id, bounds, anchors, owners, node.node("imported").getBoolean(false));
  }

  private static Optional<Bounds> bounds(ConfigurationNode node, String dimension, String path)
      throws Invalid {
    if (node.virtual() || node.isNull()) {
      return Optional.empty();
    }
    Position min = xyz(node.node("min"), dimension, 0f, path + ".min");
    Position max = xyz(node.node("max"), dimension, 0f, path + ".max");
    return Optional.of(
        Bounds.between(
            dimension,
            min.blockX(),
            min.blockY(),
            min.blockZ(),
            max.blockX(),
            max.blockY(),
            max.blockZ()));
  }

  /** {@code { pos: [x, y, z], yaw: 180 }}. */
  private static Position point(ConfigurationNode node, String dimension, String path)
      throws Invalid {
    return xyz(node.node("pos"), dimension, (float) node.node("yaw").getDouble(0), path + ".pos");
  }

  private static Position xyz(ConfigurationNode node, String dimension, float yaw, String path)
      throws Invalid {
    List<? extends ConfigurationNode> parts = node.childrenList();
    if (parts.size() != 3) {
      throw new Invalid(path, "needs three whole numbers, [x, y, z]");
    }
    int[] xyz = new int[3];
    for (int i = 0; i < 3; i++) {
      if (!(parts.get(i).raw() instanceof Number number)) {
        throw new Invalid(path, "needs three whole numbers, [x, y, z]");
      }
      xyz[i] = number.intValue();
    }
    return new Position(dimension, xyz[0], xyz[1], xyz[2], yaw, 0f);
  }

  /** Writes a gallery's file atomically, keeping the last copies in {@code backupDir}. */
  public static void write(Path file, Path backupDir, Gallery gallery) throws IOException {
    ConfigurationNode node = YamlConfigurationLoader.builder().build().createNode();
    node.node("schema-version").set(ConfigLoader.SCHEMA_VERSION);
    node.node("id").set(gallery.id());
    node.node("kind").set(gallery.kind().name().toLowerCase(Locale.ROOT));
    node.node("season").set(gallery.season());
    node.node("dimension").set(gallery.dimension());
    if (gallery.bounds().isPresent()) {
      setBounds(node.node("bounds"), gallery.bounds().get());
    }
    if (gallery.entry().isPresent()) {
      setPoint(node.node("entry"), gallery.entry().get());
    }
    for (Position exit : gallery.exits()) {
      setXyz(node.node("exits").appendListNode(), exit);
    }
    node.node("cells").raw(new java.util.LinkedHashMap<>());
    for (Cell cell : gallery.cells()) {
      ConfigurationNode at = node.node("cells", cell.id());
      if (cell.bounds().isPresent()) {
        setBounds(at.node("bounds"), cell.bounds().get());
      }
      if (cell.imported()) {
        at.node("imported").set(true);
      }
      for (Anchor anchor : cell.anchors()) {
        ConfigurationNode a = at.node("anchors").appendListNode();
        a.node("type").set(anchor.type().id());
        setPoint(a, anchor.at());
        if (anchor.page().isPresent()) {
          a.node("page").set(anchor.page().getAsInt());
        }
      }
      for (Map.Entry<Integer, PlayerId> owner : new java.util.TreeMap<>(cell.owners()).entrySet()) {
        at.node("owners", Integer.toString(owner.getKey())).set(owner.getValue().toString());
      }
      if (at.virtual() || at.empty()) {
        at.raw(new java.util.LinkedHashMap<>());
      }
    }
    StringWriter out = new StringWriter();
    YamlConfigurationLoader.builder()
        .nodeStyle(NodeStyle.BLOCK)
        .sink(() -> new BufferedWriter(out))
        .build()
        .save(node);
    AtomicFiles.writeKeepingBackups(file, out.toString().getBytes(UTF_8), backupDir);
  }

  private static void setBounds(ConfigurationNode node, Bounds bounds)
      throws SerializationException {
    setXyz(node.node("min"), bounds.min());
    setXyz(node.node("max"), bounds.max());
  }

  private static void setPoint(ConfigurationNode node, Position at) throws SerializationException {
    setXyz(node.node("pos"), at);
    if (at.yaw() != 0f) {
      node.node("yaw").set(Math.round(at.yaw()));
    }
  }

  private static void setXyz(ConfigurationNode node, Position at) throws SerializationException {
    node.setList(Integer.class, List.of(at.blockX(), at.blockY(), at.blockZ()));
  }
}
