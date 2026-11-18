// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.zone;

import com.petepeterepeat.seasons.core.config.Durations;
import com.petepeterepeat.seasons.core.config.ZonedTimes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

/**
 * Zones and Homecoming in a season file. Commands and hand edits share the format, so a zone
 * written by {@code /season zone} reads back the same, and a hand edit takes effect with {@code
 * /season reload}.
 *
 * <pre>
 * zones:
 *   old-world:  { dimension: minecraft:overworld, shape: { cylinder: { center: [0, 0], radius: 12000 } }, mode: block, lockedUntil: homecoming, afterUnlock: off }
 *   old-nether: { dimension: minecraft:the_nether, shape: { cylinder: { center: [0, 0], radius: 1500, maxY: 127 } } }
 *   far-build:  { dimension: minecraft:overworld, shape: { box: { min: [9000, -64, 200], max: [9100, 320, 300] } }, exempt: [{ min: [9010, 60, 210], max: [9020, 80, 220] }] }
 * homecoming:
 *   unlock: { manual: true }
 * </pre>
 *
 * <p>An unlock condition is {@code homecoming}, {@code manual}, or one of {@code { date: <ISO time
 * with zone> }}, {@code { after: 21d }}, {@code { dragonKilled: true }}, {@code {
 * playersReachedEnd: 3 }}, {@code { any: [...] }} and {@code { all: [...] }}.
 */
public final class ZoneFiles {
  private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

  private ZoneFiles() {}

  /** A value that doesn't make sense, at a key path such as {@code zones.old-world.shape}. */
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

  /** Every zone under a season file's {@code zones} key, by id. */
  public static Map<String, Zone> zones(ConfigurationNode node) throws Invalid {
    Map<String, Zone> zones = new TreeMap<>();
    for (Map.Entry<Object, ? extends ConfigurationNode> entry : node.childrenMap().entrySet()) {
      String id = String.valueOf(entry.getKey());
      zones.put(id, zone(id, entry.getValue(), "zones." + id));
    }
    return zones;
  }

  /** A season file's {@code homecoming.unlock}: manual unless it says otherwise. */
  public static UnlockCondition homecoming(ConfigurationNode node) throws Invalid {
    ConfigurationNode unlock = node.node("unlock");
    if (unlock.virtual() || unlock.isNull()) {
      return new UnlockCondition.Manual();
    }
    UnlockCondition condition = condition(unlock, "homecoming.unlock");
    if (condition instanceof UnlockCondition.Homecoming) {
      throw new Invalid("homecoming.unlock", "Homecoming can't wait for itself");
    }
    return condition;
  }

  private static Zone zone(String id, ConfigurationNode node, String path) throws Invalid {
    if (!Zone.validId(id)) {
      throw new Invalid(
          path, "zone ids are lower-case letters, digits and dashes, and not " + Zone.HOMECOMING);
    }
    if (!node.isMap()) {
      throw new Invalid(path, "a zone needs at least a dimension and a shape");
    }
    String dimension = node.node("dimension").getString("minecraft:overworld");
    if (!DIMENSION.matcher(dimension).matches()) {
      throw new Invalid(path + ".dimension", "'" + dimension + "' is not a dimension id");
    }
    ZoneShape shape = shape(node.node("shape"), path + ".shape");
    ZoneMode mode = mode(node.node("mode"), ZoneMode.BLOCK, path + ".mode");
    ZoneMode after = mode(node.node("afterUnlock"), ZoneMode.OFF, path + ".afterUnlock");
    if (after != ZoneMode.OFF && after != ZoneMode.NO_BUILD) {
      throw new Invalid(path + ".afterUnlock", "is off or no-build, not " + after.id());
    }
    ConfigurationNode until = node.node("lockedUntil");
    UnlockCondition lockedUntil =
        until.virtual() || until.isNull()
            ? new UnlockCondition.Homecoming()
            : condition(until, path + ".lockedUntil");
    List<ZoneShape.Box> exempt = new ArrayList<>();
    List<? extends ConfigurationNode> boxes = node.node("exempt").childrenList();
    for (int i = 0; i < boxes.size(); i++) {
      exempt.add(box(boxes.get(i), path + ".exempt." + i));
    }
    String message = node.node("message").getString();
    return new Zone(
        id,
        dimension,
        shape,
        mode,
        lockedUntil,
        after,
        exempt,
        message == null || message.isBlank() ? Optional.empty() : Optional.of(message));
  }

  private static ZoneShape shape(ConfigurationNode node, String path) throws Invalid {
    if (!node.isMap() || node.childrenMap().size() != 1) {
      throw new Invalid(path, "a shape is one of cylinder or box");
    }
    String kind = String.valueOf(node.childrenMap().keySet().iterator().next());
    ConfigurationNode shape = node.node(kind);
    return switch (kind) {
      case "cylinder" -> cylinder(shape, path + ".cylinder");
      case "box" -> box(shape, path + ".box");
      case "outside", "y-above", "y-below" ->
          throw new Invalid(path, kind + " isn't built yet; cylinder and box are");
      default -> throw new Invalid(path, "a shape is one of cylinder or box, not " + kind);
    };
  }

  private static ZoneShape.Cylinder cylinder(ConfigurationNode node, String path) throws Invalid {
    double[] center = numbers(node.node("center"), 2, path + ".center", "[x, z]");
    Object radius = node.node("radius").raw();
    if (!(radius instanceof Number number) || !(number.doubleValue() > 0)) {
      throw new Invalid(path + ".radius", "needs a number of blocks more than 0");
    }
    OptionalInt minY = whole(node.node("minY"), path + ".minY");
    OptionalInt maxY = whole(node.node("maxY"), path + ".maxY");
    try {
      return new ZoneShape.Cylinder(center[0], center[1], number.doubleValue(), minY, maxY);
    } catch (IllegalArgumentException e) {
      throw new Invalid(path, String.valueOf(e.getMessage()));
    }
  }

  private static ZoneShape.Box box(ConfigurationNode node, String path) throws Invalid {
    double[] min = numbers(node.node("min"), 3, path + ".min", "[x, y, z]");
    double[] max = numbers(node.node("max"), 3, path + ".max", "[x, y, z]");
    return ZoneShape.Box.between(
        (int) Math.floor(min[0]),
        (int) Math.floor(min[1]),
        (int) Math.floor(min[2]),
        (int) Math.floor(max[0]),
        (int) Math.floor(max[1]),
        (int) Math.floor(max[2]));
  }

  private static double[] numbers(ConfigurationNode node, int count, String path, String form)
      throws Invalid {
    List<? extends ConfigurationNode> list = node.childrenList();
    if (list.size() != count) {
      throw new Invalid(path, "needs " + count + " numbers, " + form);
    }
    double[] out = new double[count];
    for (int i = 0; i < count; i++) {
      if (!(list.get(i).raw() instanceof Number number)) {
        throw new Invalid(path, "needs " + count + " numbers, " + form);
      }
      out[i] = number.doubleValue();
    }
    return out;
  }

  private static OptionalInt whole(ConfigurationNode node, String path) throws Invalid {
    if (node.virtual() || node.isNull()) {
      return OptionalInt.empty();
    }
    if (!(node.raw() instanceof Number number)) {
      throw new Invalid(path, "needs a whole number");
    }
    return OptionalInt.of(number.intValue());
  }

  /**
   * A mode, or the default when the key is missing. YAML reads a bare {@code off} as false, so
   * false is off too.
   */
  private static ZoneMode mode(ConfigurationNode node, ZoneMode fallback, String path)
      throws Invalid {
    if (node.virtual() || node.isNull()) {
      return fallback;
    }
    Object raw = node.raw();
    if (Boolean.FALSE.equals(raw)) {
      return ZoneMode.OFF;
    }
    String text = String.valueOf(raw);
    return ZoneMode.of(text)
        .orElseThrow(() -> new Invalid(path, "is off, announce, block or no-build, not " + text));
  }

  private static UnlockCondition condition(ConfigurationNode node, String path) throws Invalid {
    if (!node.isMap()) {
      String word = String.valueOf(node.raw());
      return switch (word) {
        case "homecoming" -> new UnlockCondition.Homecoming();
        case "manual" -> new UnlockCondition.Manual();
        default ->
            throw new Invalid(
                path,
                "is homecoming, manual, or one of date, after, dragonKilled, playersReachedEnd,"
                    + " any and all");
      };
    }
    if (node.childrenMap().size() != 1) {
      throw new Invalid(path, "names one kind of condition; combine them with any or all");
    }
    String kind = String.valueOf(node.childrenMap().keySet().iterator().next());
    ConfigurationNode value = node.node(kind);
    String at = path + "." + kind;
    try {
      return switch (kind) {
        case "manual" -> new UnlockCondition.Manual();
        case "homecoming" -> new UnlockCondition.Homecoming();
        case "date" -> new UnlockCondition.At(ZonedTimes.parse(value.getString("")));
        case "after" -> new UnlockCondition.After(Durations.parse(value.getString("")));
        case "dragonKilled" -> new UnlockCondition.DragonKilled();
        case "playersReachedEnd" -> {
          if (!(value.raw() instanceof Number number) || number.intValue() < 1) {
            throw new Invalid(at, "needs a number of players, 1 or more");
          }
          yield new UnlockCondition.PlayersReachedEnd(number.intValue());
        }
        case "any", "all" -> {
          List<UnlockCondition> parts = new ArrayList<>();
          List<? extends ConfigurationNode> list = value.childrenList();
          if (list.isEmpty()) {
            throw new Invalid(at, "needs a list of conditions");
          }
          for (int i = 0; i < list.size(); i++) {
            parts.add(condition(list.get(i), at + "." + i));
          }
          yield kind.equals("any")
              ? new UnlockCondition.Any(parts)
              : new UnlockCondition.All(parts);
        }
        default ->
            throw new Invalid(
                path,
                "is homecoming, manual, or one of date, after, dragonKilled, playersReachedEnd,"
                    + " any and all, not "
                    + kind);
      };
    } catch (IllegalArgumentException e) {
      throw new Invalid(at, String.valueOf(e.getMessage()));
    }
  }

  /** Writes a zone at its key, replacing what was there. */
  public static void write(ConfigurationNode zones, Zone zone) throws SerializationException {
    ConfigurationNode node = zones.node(zone.id());
    node.raw(null);
    node.node("dimension").set(zone.dimension());
    switch (zone.shape()) {
      case ZoneShape.Cylinder cylinder -> {
        ConfigurationNode at = node.node("shape", "cylinder");
        at.node("center").appendListNode().set(number(cylinder.centerX()));
        at.node("center").appendListNode().set(number(cylinder.centerZ()));
        at.node("radius").set(number(cylinder.radius()));
        if (cylinder.minY().isPresent()) {
          at.node("minY").set(cylinder.minY().getAsInt());
        }
        if (cylinder.maxY().isPresent()) {
          at.node("maxY").set(cylinder.maxY().getAsInt());
        }
      }
      case ZoneShape.Box box -> writeBox(node.node("shape", "box"), box);
    }
    node.node("mode").set(zone.mode().id());
    writeCondition(node.node("lockedUntil"), zone.lockedUntil());
    node.node("afterUnlock").set(zone.afterUnlock().id());
    for (ZoneShape.Box box : zone.exempt()) {
      writeBox(node.node("exempt").appendListNode(), box);
    }
    if (zone.message().isPresent()) {
      node.node("message").set(zone.message().get());
    }
  }

  /** A whole number as one, so files read {@code 500} rather than {@code 500.0}. */
  private static Number number(double value) {
    return value == Math.rint(value) && Math.abs(value) < Integer.MAX_VALUE
        ? (Number) (int) value
        : (Number) value;
  }

  private static void writeBox(ConfigurationNode node, ZoneShape.Box box)
      throws SerializationException {
    node.node("min").setList(Integer.class, List.of(box.minX(), box.minY(), box.minZ()));
    node.node("max").setList(Integer.class, List.of(box.maxX(), box.maxY(), box.maxZ()));
  }

  private static void writeCondition(ConfigurationNode node, UnlockCondition condition)
      throws SerializationException {
    switch (condition) {
      case UnlockCondition.Homecoming h -> node.set("homecoming");
      case UnlockCondition.Manual m -> node.set("manual");
      case UnlockCondition.At at -> node.node("date").set(ZonedTimes.format(at.time()));
      case UnlockCondition.After after -> node.node("after").set(Durations.format(after.after()));
      case UnlockCondition.DragonKilled d -> node.node("dragonKilled").set(true);
      case UnlockCondition.PlayersReachedEnd p -> node.node("playersReachedEnd").set(p.players());
      case UnlockCondition.Any any -> {
        for (UnlockCondition part : any.any()) {
          writeCondition(node.node("any").appendListNode(), part);
        }
      }
      case UnlockCondition.All all -> {
        for (UnlockCondition part : all.all()) {
          writeCondition(node.node("all").appendListNode(), part);
        }
      }
    }
  }
}
