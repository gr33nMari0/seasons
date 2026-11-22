// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.petepeterepeat.seasons.core.config.ConfigLoader;
import com.petepeterepeat.seasons.core.config.Durations;
import com.petepeterepeat.seasons.core.config.SeasonsConfig;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.store.AtomicFiles;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
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
 * launch: { pos: [30, -60, 21] }    # optional: the launch button
 * assignment: claim                 # optional, as cells.assignment in config.yml
 * access: gate                      # optional, as cells.access
 * renderer: armour-stand            # optional: mannequin (the default) or armour-stand
 * decorating: { lockAtEnd: true, afterArchive: 15m }   # optional, as decorating in config.yml
 * cells:
 *   c1:
 *     bounds: { min: [20, -61, 22], max: [24, -55, 30] }
 *     anchors:
 *       - { type: figure, pos: [22, -60, 28], yaw: 180 }
 *       - { type: archive, pos: [23, -60, 28] }
 *       - { type: trophy, pos: [21, -59, 28], egg: 3 }   # in a museum: Season 3's egg plinth
 *     owners: { "2": 7c2a... }
 * </pre>
 */
public final class GalleryFiles {
  /** Gallery and cell ids: lower-case letters, digits, dashes and underscores. */
  public static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");

  /** The smallest and largest a plaque's text may be (questions 278). */
  public static final double MIN_SCALE = 0.25;

  public static final double MAX_SCALE = 4;

  static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

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
      throw new Invalid("kind", "is season, museum or legacy, not " + kindName);
    }
    int season = node.node("season").getInt(0);
    if (kind != Gallery.Kind.MUSEUM && season < 1) {
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
    Optional<Bounds> entryArea = area(node.node("entry"), entry, dimension, "entry");
    List<Position> exits = new ArrayList<>();
    List<Bounds> exitAreas = new ArrayList<>();
    readExits(node.node("exits"), dimension, exits, exitAreas);
    List<Cell> cells = new ArrayList<>();
    for (Map.Entry<Object, ? extends ConfigurationNode> entry2 :
        node.node("cells").childrenMap().entrySet()) {
      String cellId = String.valueOf(entry2.getKey());
      cells.add(cell(cellId, entry2.getValue(), dimension, "cells." + cellId));
    }
    Optional<Position> launch =
        node.node("launch").virtual()
            ? Optional.empty()
            : Optional.of(point(node.node("launch"), dimension, "launch"));
    return new Gallery(
        id,
        kind,
        season,
        dimension,
        bounds,
        entry,
        exits,
        cells,
        settings(node),
        launch,
        new Gallery.Areas(entryArea, exitAreas));
  }

  /**
   * Exits as a list of single blocks {@code [x, y, z]} and areas {@code { min, max }} (questions
   * 279), each at most 32 blocks a side.
   */
  static void readExits(
      ConfigurationNode node, String dimension, List<Position> exits, List<Bounds> areas)
      throws Invalid {
    List<? extends ConfigurationNode> exitNodes = node.childrenList();
    for (int i = 0; i < exitNodes.size(); i++) {
      ConfigurationNode exit = exitNodes.get(i);
      if (exit.isMap()) {
        Bounds area = bounds(exit, dimension, "exits." + i).orElseThrow();
        checkArea(area, "exits." + i);
        areas.add(area);
      } else {
        exits.add(xyz(exit, dimension, 0f, "exits." + i));
      }
    }
  }

  /** Writes exits as {@link #readExits} reads them. */
  static void writeExits(ConfigurationNode node, List<Position> exits, List<Bounds> areas)
      throws SerializationException {
    for (Position exit : exits) {
      setXyz(node.appendListNode(), exit);
    }
    for (Bounds area : areas) {
      setBounds(node.appendListNode(), area);
    }
  }

  /** The largest an entry, exit or link area may be along each side (questions 279). */
  public static final int MAX_AREA = 32;

  static void checkArea(Bounds area, String path) throws Invalid {
    if (area.maxX() - area.minX() >= MAX_AREA
        || area.maxY() - area.minY() >= MAX_AREA
        || area.maxZ() - area.minZ() >= MAX_AREA) {
      throw new Invalid(path, "an area is at most " + MAX_AREA + " blocks a side");
    }
  }

  /** A point's box, when it has a {@code to} corner: from {@code pos} to {@code to}. */
  static Optional<Bounds> area(
      ConfigurationNode node, Optional<Position> from, String dimension, String path)
      throws Invalid {
    if (from.isEmpty() || node.node("to").virtual()) {
      return Optional.empty();
    }
    Position to = xyz(node.node("to"), dimension, 0f, path + ".to");
    Position a = from.get();
    Bounds area =
        Bounds.between(
            dimension, a.blockX(), a.blockY(), a.blockZ(), to.blockX(), to.blockY(), to.blockZ());
    checkArea(area, path + ".to");
    return Optional.of(area);
  }

  /** Writes a point and, for a box, its far corner {@code to}. */
  static void setPoint(ConfigurationNode node, Position at, Optional<Bounds> area)
      throws SerializationException {
    setPoint(node, at);
    if (area.isPresent()) {
      Bounds b = area.get();
      Position far =
          b.min().blockX() == at.blockX()
                  && b.min().blockY() == at.blockY()
                  && b.min().blockZ() == at.blockZ()
              ? b.max()
              : b.min();
      setXyz(node.node("to"), far);
    }
  }

  private static Gallery.Settings settings(ConfigurationNode node) throws Invalid {
    Optional<SeasonsConfig.CellAssignment> assignment =
        constant(node.node("assignment"), SeasonsConfig.CellAssignment.class, "assignment");
    Optional<SeasonsConfig.CellAccess> access =
        constant(node.node("access"), SeasonsConfig.CellAccess.class, "access");
    Optional<FigureRenderer> renderer = Optional.empty();
    if (!node.node("renderer").virtual()) {
      String name = node.node("renderer").getString("");
      renderer =
          Optional.of(
              FigureRenderer.of(name)
                  .orElseThrow(() ->
                      new Invalid("renderer", "is mannequin or armour-stand, not '" + name + "'")));
    }
    ConfigurationNode decorating = node.node("decorating");
    Optional<Boolean> lockAtEnd = Optional.empty();
    if (!decorating.node("lockAtEnd").virtual()) {
      if (!(decorating.node("lockAtEnd").raw() instanceof Boolean value)) {
        throw new Invalid("decorating.lockAtEnd", "is true or false");
      }
      lockAtEnd = Optional.of(value);
    }
    Optional<Duration> afterArchive = Optional.empty();
    if (!decorating.node("afterArchive").virtual()) {
      String text = String.valueOf(decorating.node("afterArchive").raw());
      try {
        afterArchive = Optional.of(Durations.parse(text));
      } catch (IllegalArgumentException e) {
        throw new Invalid("decorating.afterArchive", String.valueOf(e.getMessage()));
      }
    }
    Optional<Double> plaqueScale = Optional.empty();
    if (!node.node("plaqueScale").virtual()) {
      double scale = node.node("plaqueScale").getDouble(Double.NaN);
      if (!(scale >= MIN_SCALE && scale <= MAX_SCALE)) {
        throw new Invalid("plaqueScale", "is between 0.25 and 4");
      }
      plaqueScale = Optional.of(scale);
    }
    return new Gallery.Settings(assignment, access, renderer, lockAtEnd, afterArchive, plaqueScale);
  }

  /** An enum written in lower case with dashes, such as {@code archive-and-reset}. */
  private static <E extends Enum<E>> Optional<E> constant(
      ConfigurationNode node, Class<E> type, String path) throws Invalid {
    if (node.virtual()) {
      return Optional.empty();
    }
    String name = node.getString("");
    for (E constant : type.getEnumConstants()) {
      if (dashed(constant).equals(name)) {
        return Optional.of(constant);
      }
    }
    throw new Invalid(
        path,
        "is one of "
            + String.join(
                ", ", Arrays.stream(type.getEnumConstants()).map(GalleryFiles::dashed).toList())
            + ", not '"
            + name
            + "'");
  }

  private static String dashed(Enum<?> constant) {
    return constant.name().toLowerCase(Locale.ROOT).replace('_', '-');
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
      OptionalInt egg = OptionalInt.empty();
      if (!anchor.node("egg").virtual()) {
        int season = anchor.node("egg").getInt(0);
        if (type != AnchorType.TROPHY || season < 1) {
          throw new Invalid(
              at + ".egg", "is a season number, and only a trophy anchor can be an egg plinth");
        }
        egg = OptionalInt.of(season);
      }
      Optional<Bounds> area = area(anchor, Optional.of(position), dimension, at);
      if (area.isPresent() && type != AnchorType.ENTRY) {
        throw new Invalid(at + ".to", "only an entry anchor can be an area");
      }
      java.util.OptionalDouble scale = java.util.OptionalDouble.empty();
      if (!anchor.node("scale").virtual()) {
        double value = anchor.node("scale").getDouble(Double.NaN);
        if (type != AnchorType.PLAQUE || !(value >= MIN_SCALE && value <= MAX_SCALE)) {
          throw new Invalid(at + ".scale", "only a plaque has a scale, between 0.25 and 4");
        }
        scale = java.util.OptionalDouble.of(value);
      }
      anchors.add(new Anchor(type, position, page, egg, area, scale));
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
    Optional<String> pose = Optional.empty();
    if (!node.node("pose").virtual()) {
      String name = node.node("pose").getString("");
      if (!Cell.POSES.contains(name)) {
        throw new Invalid(
            path + ".pose", "is one of " + String.join(", ", Cell.POSES) + ", not '" + name + "'");
      }
      pose = name.equals("standing") ? Optional.empty() : Optional.of(name);
    }
    boolean shut = false;
    if (!node.node("gate").virtual()) {
      String gate = node.node("gate").getString("");
      if (!gate.equals("shut") && !gate.equals("open")) {
        throw new Invalid(path + ".gate", "is open or shut, not '" + gate + "'");
      }
      shut = gate.equals("shut");
    }
    return new Cell(
        id, bounds, anchors, owners, node.node("imported").getBoolean(false), pose, shut);
  }

  static Optional<Bounds> bounds(ConfigurationNode node, String dimension, String path)
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
  static Position point(ConfigurationNode node, String dimension, String path) throws Invalid {
    return xyz(node.node("pos"), dimension, (float) node.node("yaw").getDouble(0), path + ".pos");
  }

  static Position xyz(ConfigurationNode node, String dimension, float yaw, String path)
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
      setPoint(node.node("entry"), gallery.entry().get(), gallery.areas().entry());
    }
    writeExits(node.node("exits"), gallery.exits(), gallery.areas().exits());
    if (gallery.launch().isPresent()) {
      setPoint(node.node("launch"), gallery.launch().get());
    }
    Gallery.Settings settings = gallery.settings();
    if (settings.assignment().isPresent()) {
      node.node("assignment").set(dashed(settings.assignment().get()));
    }
    if (settings.access().isPresent()) {
      node.node("access").set(dashed(settings.access().get()));
    }
    if (settings.renderer().isPresent()) {
      node.node("renderer").set(settings.renderer().get().id());
    }
    if (settings.lockAtEnd().isPresent()) {
      node.node("decorating", "lockAtEnd").set(settings.lockAtEnd().get());
    }
    if (settings.afterArchive().isPresent()) {
      node.node("decorating", "afterArchive").set(Durations.format(settings.afterArchive().get()));
    }
    if (settings.plaqueScale().isPresent()) {
      node.node("plaqueScale").set(settings.plaqueScale().get());
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
      if (cell.pose().isPresent()) {
        at.node("pose").set(cell.pose().get());
      }
      if (cell.gateShut()) {
        at.node("gate").set("shut");
      }
      for (Anchor anchor : cell.anchors()) {
        ConfigurationNode a = at.node("anchors").appendListNode();
        a.node("type").set(anchor.type().id());
        setPoint(a, anchor.at(), anchor.area());
        if (anchor.scale().isPresent()) {
          a.node("scale").set(anchor.scale().getAsDouble());
        }
        if (anchor.page().isPresent()) {
          a.node("page").set(anchor.page().getAsInt());
        }
        if (anchor.egg().isPresent()) {
          a.node("egg").set(anchor.egg().getAsInt());
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

  static void setBounds(ConfigurationNode node, Bounds bounds) throws SerializationException {
    setXyz(node.node("min"), bounds.min());
    setXyz(node.node("max"), bounds.max());
  }

  static void setPoint(ConfigurationNode node, Position at) throws SerializationException {
    setXyz(node.node("pos"), at);
    if (at.yaw() != 0f) {
      node.node("yaw").set(Math.round(at.yaw()));
    }
  }

  static void setXyz(ConfigurationNode node, Position at) throws SerializationException {
    node.setList(Integer.class, List.of(at.blockX(), at.blockY(), at.blockZ()));
  }
}
