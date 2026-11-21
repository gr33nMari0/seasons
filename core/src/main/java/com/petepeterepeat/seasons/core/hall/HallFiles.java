// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.petepeterepeat.seasons.core.config.ConfigLoader;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.store.AtomicFiles;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Reads and writes {@code hall.yml} (questions 243). Commands and hand edits share the format, as
 * gallery files do.
 *
 * <pre>
 * schema-version: 1
 * dimension: minecraft:overworld
 * hub: { min: [-12, 250, -12], max: [12, 290, 12] }
 * spawn: { pos: [0, 256, 0], yaw: 180 }
 * exits: [[0, 256, 10]]
 * links:
 *   s3: { pos: [3, 256, 2], to: { season: 3 } }
 *   s1: { pos: [3, 256, 4], to: { pos: [120, 70, -300], yaw: 90 } }
 *   back: { dimension: minecraft:overworld, pos: [18452, 72, -9120], to: hall }
 * </pre>
 */
public final class HallFiles {
  /** Link ids, as gallery and cell ids. */
  public static final Pattern ID = GalleryFiles.ID;

  private static final String HALL = "hall";

  private HallFiles() {}

  /** The Hall's parts from {@code hall.yml}'s root node. */
  public static HallLayout read(ConfigurationNode node) throws GalleryFiles.Invalid {
    String dimension = dimension(node.node("dimension"), HallLayout.OVERWORLD, "dimension");
    Optional<Bounds> hub = GalleryFiles.bounds(node.node("hub"), dimension, "hub");
    Optional<Position> spawn =
        node.node("spawn").virtual()
            ? Optional.empty()
            : Optional.of(GalleryFiles.point(node.node("spawn"), dimension, "spawn"));
    List<Position> exits = new ArrayList<>();
    List<? extends ConfigurationNode> exitNodes = node.node("exits").childrenList();
    for (int i = 0; i < exitNodes.size(); i++) {
      exits.add(GalleryFiles.xyz(exitNodes.get(i), dimension, 0f, "exits." + i));
    }
    Map<String, HallLayout.Link> links = new LinkedHashMap<>();
    for (Map.Entry<Object, ? extends ConfigurationNode> entry :
        node.node("links").childrenMap().entrySet()) {
      String id = String.valueOf(entry.getKey());
      String path = "links." + id;
      if (!ID.matcher(id).matches()) {
        throw new GalleryFiles.Invalid(path, "link ids are lower-case letters, digits, - and _");
      }
      ConfigurationNode link = entry.getValue();
      String at = dimension(link.node("dimension"), dimension, path + ".dimension");
      Position block = GalleryFiles.xyz(link.node("pos"), at, 0f, path + ".pos");
      links.put(id, new HallLayout.Link(id, block, target(link.node("to"), at, path + ".to")));
    }
    return new HallLayout(dimension, hub, spawn, exits, links);
  }

  private static Optional<HallLayout.Target> target(
      ConfigurationNode node, String dimension, String path) throws GalleryFiles.Invalid {
    if (node.virtual() || node.isNull()) {
      return Optional.empty();
    }
    if (!node.isMap()) {
      if (HALL.equals(node.getString())) {
        return Optional.of(new HallLayout.Target.Hall());
      }
      throw new GalleryFiles.Invalid(
          path, "is hall, { season: <n> } or { pos: [x, y, z], yaw: <yaw> }");
    }
    if (!node.node("season").virtual()) {
      int season = node.node("season").getInt(0);
      if (season < 1) {
        throw new GalleryFiles.Invalid(path + ".season", "is a season number");
      }
      return Optional.of(new HallLayout.Target.SeasonSpawn(season));
    }
    String at = dimension(node.node("dimension"), dimension, path + ".dimension");
    return Optional.of(new HallLayout.Target.At(GalleryFiles.point(node, at, path)));
  }

  private static String dimension(ConfigurationNode node, String fallback, String path)
      throws GalleryFiles.Invalid {
    String dimension = node.getString(fallback);
    if (!GalleryFiles.DIMENSION.matcher(dimension).matches()) {
      throw new GalleryFiles.Invalid(path, "'" + dimension + "' is not a dimension id");
    }
    return dimension;
  }

  /** Writes {@code hall.yml} atomically, keeping the last copies in {@code backupDir}. */
  public static void write(Path file, Path backupDir, HallLayout hall) throws IOException {
    ConfigurationNode node = YamlConfigurationLoader.builder().build().createNode();
    node.node("schema-version").set(ConfigLoader.SCHEMA_VERSION);
    node.node("dimension").set(hall.dimension());
    if (hall.hub().isPresent()) {
      GalleryFiles.setBounds(node.node("hub"), hall.hub().get());
    }
    if (hall.spawn().isPresent()) {
      GalleryFiles.setPoint(node.node("spawn"), hall.spawn().get());
    }
    for (Position exit : hall.exits()) {
      GalleryFiles.setXyz(node.node("exits").appendListNode(), exit);
    }
    for (HallLayout.Link link : hall.links().values()) {
      ConfigurationNode at = node.node("links", link.id());
      if (!link.block().dimension().equals(hall.dimension())) {
        at.node("dimension").set(link.block().dimension());
      }
      GalleryFiles.setXyz(at.node("pos"), link.block());
      if (link.to().isPresent()) {
        setTarget(at.node("to"), link.to().get(), link.block().dimension());
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

  private static void setTarget(ConfigurationNode node, HallLayout.Target to, String dimension)
      throws SerializationException {
    switch (to) {
      case HallLayout.Target.Hall() -> node.set(HALL);
      case HallLayout.Target.SeasonSpawn(int season) -> node.node("season").set(season);
      case HallLayout.Target.At(Position at) -> {
        if (!at.dimension().equals(dimension)) {
          node.node("dimension").set(at.dimension());
        }
        GalleryFiles.setPoint(node, at);
      }
    }
  }
}
