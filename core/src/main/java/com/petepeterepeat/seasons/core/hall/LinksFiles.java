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
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Reads and writes {@code <world>/seasons/links.yml} (M8): the links, with their triggers, and the
 * named points. Commands and hand edits share the format, as gallery files do.
 *
 * <pre>
 * schema-version: 1
 * upgraded: { from: hall.yml, at: "2026-11-24T19:00:00Z", done: false, before: { s3: both } }
 * points:
 *   s1-steps: { dimension: minecraft:overworld, pos: [512, 70, -300], yaw: 90 }
 * links:
 *   hof-s1:
 *     name: "&lt;gold&gt;Season 1"
 *     to: { season: 1 }
 *     access: everyone
 *     pair: hof-s1-back
 *     triggers:
 *       - { use: { dimension: minecraft:overworld, pos: [3, 257, -8] } }
 *       - { area: { dimension: minecraft:overworld, min: [10, 256, -2], max: [10, 259, 2] } }
 *       - { text: { pos: [0.5, 258.2, -6.5], lines: ["Season 1"], scale: 1.5 } }
 * </pre>
 */
public final class LinksFiles {
  private static final String HALL = "hall";
  private static final String RETURN = "return";

  private LinksFiles() {}

  /** What {@code links.yml} holds. */
  public record Contents(
      Map<String, HallLayout.Link> links,
      Map<String, HallLayout.Point> points,
      Optional<HallLayout.Upgrade> upgrade) {}

  /** The links, points and upgrade note from {@code links.yml}'s root node. */
  public static Contents read(ConfigurationNode node, String dimension)
      throws GalleryFiles.Invalid {
    Map<String, HallLayout.Point> points = new LinkedHashMap<>();
    for (Map.Entry<Object, ? extends ConfigurationNode> entry :
        node.node("points").childrenMap().entrySet()) {
      String name = String.valueOf(entry.getKey());
      String path = "points." + name;
      if (!HallFiles.ID.matcher(name).matches()) {
        throw new GalleryFiles.Invalid(path, "point names are lower-case letters, digits, - and _");
      }
      ConfigurationNode at = entry.getValue();
      String d = dimension(at.node("dimension"), dimension, path + ".dimension");
      Position spot = point(at, d, path);
      Optional<Bounds> area = GalleryFiles.area(at, Optional.of(spot), d, path);
      points.put(name, new HallLayout.Point(name, spot, area));
    }
    Map<String, HallLayout.Link> links = new LinkedHashMap<>();
    for (Map.Entry<Object, ? extends ConfigurationNode> entry :
        node.node("links").childrenMap().entrySet()) {
      String id = String.valueOf(entry.getKey());
      String path = "links." + id;
      if (!HallFiles.ID.matcher(id).matches()) {
        throw new GalleryFiles.Invalid(path, "link ids are lower-case letters, digits, - and _");
      }
      links.put(id, link(id, entry.getValue(), dimension, path));
    }
    Optional<HallLayout.Upgrade> upgrade = Optional.empty();
    ConfigurationNode upgraded = node.node("upgraded");
    if (!upgraded.virtual()) {
      Map<String, String> before = new LinkedHashMap<>();
      for (Map.Entry<Object, ? extends ConfigurationNode> entry :
          upgraded.node("before").childrenMap().entrySet()) {
        before.put(String.valueOf(entry.getKey()), entry.getValue().getString(""));
      }
      upgrade =
          Optional.of(
              new HallLayout.Upgrade(
                  upgraded.node("at").getString(""),
                  upgraded.node("done").getBoolean(false),
                  before,
                  upgraded.node("frozen").getString("")));
    }
    return new Contents(links, points, upgrade);
  }

  private static HallLayout.Link link(
      String id, ConfigurationNode node, String dimension, String path)
      throws GalleryFiles.Invalid {
    Optional<String> name = Optional.ofNullable(node.node("name").getString());
    Optional<HallLayout.Target> to = target(node.node("to"), dimension, path + ".to");
    HallLayout.Access access = access(node, path);
    HallLayout.When when = HallLayout.When.ALWAYS;
    if (!node.node("when").virtual()) {
      String text = node.node("when").getString("");
      when =
          HallLayout.When.parse(text)
              .orElseThrow(() ->
                  new GalleryFiles.Invalid(
                      path + ".when",
                      "is always, after-homecoming, stage frontier, stage open or played <season>"));
    }
    Optional<Position> landing = Optional.empty();
    if (!node.node("landing").virtual()) {
      ConfigurationNode at = node.node("landing");
      landing =
          Optional.of(
              point(at, dimension(at.node("dimension"), dimension, path + ".landing"), path));
    }
    Optional<String> pair = Optional.ofNullable(node.node("pair").getString());
    List<HallLayout.Trigger> triggers = new ArrayList<>();
    List<? extends ConfigurationNode> list = node.node("triggers").childrenList();
    for (int i = 0; i < list.size(); i++) {
      triggers.add(trigger(list.get(i), dimension, path + ".triggers." + i));
    }
    return new HallLayout.Link(id, name, to, triggers, access, when, landing, pair);
  }

  private static HallLayout.Access access(ConfigurationNode node, String path)
      throws GalleryFiles.Invalid {
    String word = node.node("access").getString("everyone");
    Optional<String> permission = Optional.ofNullable(node.node("node").getString());
    return switch (word) {
      case "everyone" -> HallLayout.Access.EVERYONE;
      case "op" -> HallLayout.Access.OP;
      case "permission" -> new HallLayout.Access(HallLayout.Access.Kind.PERMISSION, permission);
      default -> throw new GalleryFiles.Invalid(path + ".access", "is everyone, op or permission");
    };
  }

  private static HallLayout.Trigger trigger(ConfigurationNode node, String dimension, String path)
      throws GalleryFiles.Invalid {
    for (HallLayout.BlockKind kind : HallLayout.BlockKind.values()) {
      ConfigurationNode at = node.node(kind.word());
      if (!at.virtual()) {
        String d = dimension(at.node("dimension"), dimension, path + ".dimension");
        return new HallLayout.Trigger.OnBlock(
            GalleryFiles.xyz(at.node("pos"), d, 0f, path + ".pos"), kind);
      }
    }
    ConfigurationNode area = node.node("area");
    if (!area.virtual()) {
      String d = dimension(area.node("dimension"), dimension, path + ".dimension");
      Bounds box = GalleryFiles.bounds(area, d, path + ".area").orElseThrow();
      GalleryFiles.checkArea(box, path + ".area");
      return new HallLayout.Trigger.Area(box);
    }
    ConfigurationNode text = node.node("text");
    if (!text.virtual()) {
      String d = dimension(text.node("dimension"), dimension, path + ".dimension");
      List<String> lines = new ArrayList<>();
      for (ConfigurationNode line : text.node("lines").childrenList()) {
        lines.add(line.getString(""));
      }
      double scale = text.node("scale").getDouble(1.0);
      if (scale < 0.25 || scale > 4) {
        throw new GalleryFiles.Invalid(path + ".text.scale", "is 0.25 to 4");
      }
      return new HallLayout.Trigger.Text(
          fine(text.node("pos"), d, (float) text.node("yaw").getDouble(0), path + ".text.pos"),
          lines,
          scale,
          text.node("billboard").getString("center"),
          text.node("walk").getDouble(0),
          text.node("left").getBoolean(false));
    }
    throw new GalleryFiles.Invalid(path, "is one of use, step, both, area or text");
  }

  /** A link's destination, as 1.1 wrote them and as M8 adds. */
  static Optional<HallLayout.Target> target(ConfigurationNode node, String dimension, String path)
      throws GalleryFiles.Invalid {
    if (node.virtual() || node.isNull()) {
      return Optional.empty();
    }
    if (!node.isMap()) {
      String word = node.getString("");
      if (HALL.equals(word)) {
        return Optional.of(new HallLayout.Target.Hall());
      }
      if (RETURN.equals(word)) {
        return Optional.of(new HallLayout.Target.Return());
      }
      throw new GalleryFiles.Invalid(
          path,
          "is hall, return, { season: <n> }, { point: <name> }, { link: <id> }, { gallery: <id> }"
              + " or { pos: [x, y, z], yaw: <yaw> }");
    }
    if (!node.node("season").virtual()) {
      int season = node.node("season").getInt(0);
      if (season < 1) {
        throw new GalleryFiles.Invalid(path + ".season", "is a season number");
      }
      return Optional.of(new HallLayout.Target.SeasonSpawn(season));
    }
    if (!node.node("point").virtual()) {
      return Optional.of(new HallLayout.Target.AtPoint(node.node("point").getString("")));
    }
    if (!node.node("link").virtual()) {
      return Optional.of(new HallLayout.Target.ToLink(node.node("link").getString("")));
    }
    if (!node.node("gallery").virtual()) {
      return Optional.of(new HallLayout.Target.ToGallery(node.node("gallery").getString("")));
    }
    String at = dimension(node.node("dimension"), dimension, path + ".dimension");
    return Optional.of(new HallLayout.Target.At(point(node, at, path)));
  }

  /** {@code { pos: [x, y, z], yaw: 180, pitch: 10 }}. */
  private static Position point(ConfigurationNode node, String dimension, String path)
      throws GalleryFiles.Invalid {
    Position at = GalleryFiles.point(node, dimension, path);
    float pitch = (float) node.node("pitch").getDouble(0);
    return new Position(dimension, at.x(), at.y(), at.z(), at.yaw(), pitch);
  }

  /** {@code [x, y, z]} as any numbers, for a floating text that stands between blocks. */
  private static Position fine(ConfigurationNode node, String dimension, float yaw, String path)
      throws GalleryFiles.Invalid {
    List<? extends ConfigurationNode> parts = node.childrenList();
    if (parts.size() != 3) {
      throw new GalleryFiles.Invalid(path, "needs three numbers, [x, y, z]");
    }
    double[] xyz = new double[3];
    for (int i = 0; i < 3; i++) {
      if (!(parts.get(i).raw() instanceof Number number)) {
        throw new GalleryFiles.Invalid(path, "needs three numbers, [x, y, z]");
      }
      xyz[i] = number.doubleValue();
    }
    return new Position(dimension, xyz[0], xyz[1], xyz[2], yaw, 0f);
  }

  static String dimension(ConfigurationNode node, String fallback, String path)
      throws GalleryFiles.Invalid {
    String dimension = node.getString(fallback);
    if (!GalleryFiles.DIMENSION.matcher(dimension).matches()) {
      throw new GalleryFiles.Invalid(path, "'" + dimension + "' is not a dimension id");
    }
    return dimension;
  }

  /** Writes {@code links.yml} atomically, keeping the last copies in {@code backupDir}. */
  public static void write(Path file, Path backupDir, HallLayout hall) throws IOException {
    ConfigurationNode node = YamlConfigurationLoader.builder().build().createNode();
    node.node("schema-version").set(ConfigLoader.SCHEMA_VERSION);
    if (hall.upgrade().isPresent()) {
      HallLayout.Upgrade upgrade = hall.upgrade().get();
      ConfigurationNode at = node.node("upgraded");
      at.node("from").set("hall.yml");
      at.node("at").set(upgrade.at());
      at.node("done").set(upgrade.done());
      at.node("frozen").set(upgrade.frozen());
      for (Map.Entry<String, String> before : upgrade.before().entrySet()) {
        at.node("before", before.getKey()).set(before.getValue());
      }
    }
    for (HallLayout.Point point : hall.points().values()) {
      ConfigurationNode at = node.node("points", point.name());
      at.node("dimension").set(point.at().dimension());
      setPoint(at, point.at());
      if (point.area().isPresent()) {
        GalleryFiles.setPoint(at, point.at(), point.area());
      }
    }
    for (HallLayout.Link link : hall.links().values()) {
      ConfigurationNode at = node.node("links", link.id());
      if (link.name().isPresent()) {
        at.node("name").set(link.name().get());
      }
      if (link.to().isPresent()) {
        setTarget(at.node("to"), link.to().get());
      }
      at.node("access").set(link.access().word());
      if (link.access().node().isPresent()) {
        at.node("node").set(link.access().node().get());
      }
      if (!link.when().equals(HallLayout.When.ALWAYS)) {
        at.node("when").set(link.when().text());
      }
      if (link.landing().isPresent()) {
        at.node("landing", "dimension").set(link.landing().get().dimension());
        setPoint(at.node("landing"), link.landing().get());
      }
      if (link.pair().isPresent()) {
        at.node("pair").set(link.pair().get());
      }
      List<HallLayout.Trigger> triggers = link.triggers();
      ConfigurationNode list = at.node("triggers");
      for (HallLayout.Trigger trigger : triggers) {
        ConfigurationNode item = list.appendListNode();
        switch (trigger) {
          case HallLayout.Trigger.OnBlock(Position block, HallLayout.BlockKind kind) -> {
            ConfigurationNode on = item.node(kind.word());
            on.node("dimension").set(block.dimension());
            GalleryFiles.setXyz(on.node("pos"), block);
          }
          case HallLayout.Trigger.Area(Bounds box) -> {
            ConfigurationNode area = item.node("area");
            area.node("dimension").set(box.dimension());
            GalleryFiles.setBounds(area, box);
          }
          case HallLayout.Trigger.Text text -> {
            ConfigurationNode t = item.node("text");
            t.node("dimension").set(text.at().dimension());
            t.node("pos")
                .setList(
                    Double.class,
                    List.of(round(text.at().x()), round(text.at().y()), round(text.at().z())));
            if (text.at().yaw() != 0f) {
              t.node("yaw").set(Math.round(text.at().yaw()));
            }
            t.node("lines").setList(String.class, text.lines());
            t.node("scale").set(text.scale());
            t.node("billboard").set(text.billboard());
            if (text.walk() > 0) {
              t.node("walk").set(text.walk());
            }
            if (text.left()) {
              t.node("left").set(true);
            }
          }
        }
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

  private static double round(double value) {
    return Math.round(value * 100) / 100.0;
  }

  private static void setPoint(ConfigurationNode node, Position at) throws SerializationException {
    GalleryFiles.setPoint(node, at);
    if (at.pitch() != 0f) {
      node.node("pitch").set(Math.round(at.pitch()));
    }
  }

  static void setTarget(ConfigurationNode node, HallLayout.Target to)
      throws SerializationException {
    switch (to) {
      case HallLayout.Target.Hall() -> node.set(HALL);
      case HallLayout.Target.Return() -> node.set(RETURN);
      case HallLayout.Target.SeasonSpawn(int season) -> node.node("season").set(season);
      case HallLayout.Target.AtPoint(String point) -> node.node("point").set(point);
      case HallLayout.Target.ToLink(String link) -> node.node("link").set(link);
      case HallLayout.Target.ToGallery(String gallery) -> node.node("gallery").set(gallery);
      case HallLayout.Target.At(Position at) -> {
        node.node("dimension").set(at.dimension());
        setPoint(node, at);
      }
    }
  }
}
