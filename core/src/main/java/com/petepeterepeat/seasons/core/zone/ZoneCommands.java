// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.zone;

import com.petepeterepeat.seasons.core.command.ArgumentType;
import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.config.ConfigEdits;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.config.ConfigLoader;
import com.petepeterepeat.seasons.core.config.SeasonFile;
import com.petepeterepeat.seasons.core.config.SeasonFiles;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.season.SeasonService;
import com.petepeterepeat.seasons.core.season.Transition;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * {@code /season zone}, {@code /season unlock} and {@code /season lock}. Zone edits go to the
 * season file zone commands edit (see {@link Zones#editingSeason}) through {@link ConfigEdits}, so
 * the edit is in force at once and audited as a config change: the path, the old value, the new one
 * and who.
 */
public final class ZoneCommands {
  /** Permission for the zone commands, with the other config commands. */
  public static final String PERMISSION_CONFIG = "seasons.admin.config";

  /** Permission for unlocking and locking, with the other season commands. */
  public static final String PERMISSION_SEASON = "seasons.admin.season";

  private static final String OVERWORLD = "minecraft:overworld";

  private final Platform platform;
  private final ConfigHolder config;
  private final ConfigLoader loader;
  private final ConfigEdits edits;
  private final SeasonService seasons;
  private final Zones zones;
  private final Path backups;

  /**
   * @param backups {@code <world>/seasons/backups}
   */
  public ZoneCommands(
      Platform platform,
      ConfigHolder config,
      ConfigLoader loader,
      ConfigEdits edits,
      SeasonService seasons,
      Zones zones,
      Path backups) {
    this.platform = platform;
    this.config = config;
    this.loader = loader;
    this.edits = edits;
    this.seasons = seasons;
    this.zones = zones;
    this.backups = backups;
  }

  /** The nodes under {@code /season}: zone, unlock and lock. */
  public List<CommandNode> nodes() {
    return List.of(
        CommandNode.literal("zone")
            .permission(PERMISSION_CONFIG)
            .then(
                CommandNode.literal("add")
                    .then(
                        CommandNode.argument("id", ArgumentType.word())
                            .then(
                                CommandNode.argument("shape", ArgumentType.greedy())
                                    .executes(this::add))))
            .then(
                CommandNode.literal("mode")
                    .then(
                        CommandNode.argument("id", ArgumentType.word())
                            .then(
                                CommandNode.argument("mode", ArgumentType.word())
                                    .executes(this::mode))))
            .then(
                CommandNode.literal("after")
                    .then(
                        CommandNode.argument("id", ArgumentType.word())
                            .then(
                                CommandNode.argument("mode", ArgumentType.word())
                                    .executes(this::after))))
            .then(
                CommandNode.literal("remove")
                    .then(CommandNode.argument("id", ArgumentType.word()).executes(this::remove)))
            .then(CommandNode.literal("list").executes(this::list))
            .build(),
        CommandNode.literal("unlock")
            .permission(PERMISSION_SEASON)
            .then(CommandNode.argument("target", ArgumentType.word()).executes(this::unlock))
            .build(),
        CommandNode.literal("lock")
            .permission(PERMISSION_SEASON)
            .then(CommandNode.argument("zone", ArgumentType.word()).executes(this::lock))
            .build());
  }

  // Adding

  void add(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    OptionalInt season = zones.editingSeason();
    if (season.isEmpty()) {
      source.reply(messages.get("zone.no-season"));
      return;
    }
    String id = arguments.string("id");
    if (!Zone.validId(id)) {
      source.reply(messages.get("zone.bad-id"));
      return;
    }
    if (file(season.getAsInt()).map(f -> f.zones().containsKey(id)).orElse(false)) {
      source.reply(messages.get("zone.exists", "id", id));
      return;
    }
    List<String> words = List.of(arguments.string("shape").trim().split("\\s+"));
    String dimension =
        source.player().map(p -> platform.players().position(p).dimension()).orElse(OVERWORLD);
    Optional<ZoneShape> shape;
    switch (words.getFirst()) {
      case "cylinder" -> {
        if (source.player().isEmpty()) {
          source.reply(messages.get("zone.player-only"));
          return;
        }
        shape = cylinder(platform.players().position(source.player().get()), words);
      }
      case "box" -> {
        if (words.size() == 1) {
          source.reply(messages.get("zone.box-corners", "id", id));
          return;
        }
        shape = box(words);
      }
      default -> shape = Optional.empty();
    }
    if (shape.isEmpty()) {
      source.reply(messages.get("zone.bad-shape"));
      return;
    }
    Zone zone = Zone.drawn(id, dimension, shape.get());
    Optional<Position> spawn = file(season.getAsInt()).flatMap(SeasonFile::spawn);
    if (spawn.isPresent() && zone.contains(spawn.get())) {
      Position at = spawn.get();
      source.reply(
          messages.get(
              "zone.around-spawn",
              "season",
              Integer.toString(season.getAsInt()),
              "x",
              Integer.toString(at.blockX()),
              "y",
              Integer.toString(at.blockY()),
              "z",
              Integer.toString(at.blockZ())));
      return;
    }
    Optional<Map.Entry<String, Position>> candidate =
        file(season.getAsInt()).flatMap(f -> f.candidateIn(zone));
    if (candidate.isPresent()) {
      Position at = candidate.get().getValue();
      source.reply(
          messages.get(
              "zone.around-candidate",
              "season",
              Integer.toString(season.getAsInt()),
              "name",
              candidate.get().getKey(),
              "x",
              Integer.toString(at.blockX()),
              "y",
              Integer.toString(at.blockY()),
              "z",
              Integer.toString(at.blockZ())));
      return;
    }
    int number = season.getAsInt();
    edit(
        source,
        number,
        new Change("zones." + id, NONE, summary(zone)),
        file -> SeasonFiles.writeZone(file, backups, number, zone),
        () ->
            source.reply(
                messages()
                    .get(
                        "zone.added",
                        "id",
                        id,
                        "shape",
                        describe(zone.shape()),
                        "dimension",
                        dimension,
                        "mode",
                        zone.mode().id(),
                        "after",
                        zone.afterUnlock().id())));
  }

  /** {@code cylinder <radius> [--max-y <y>] [--min-y <y>]}, centred on the block at {@code at}. */
  private static Optional<ZoneShape> cylinder(Position at, List<String> words) {
    if (words.size() < 2 || words.size() % 2 != 0) {
      return Optional.empty();
    }
    try {
      int radius = Integer.parseInt(words.get(1));
      OptionalInt minY = OptionalInt.empty();
      OptionalInt maxY = OptionalInt.empty();
      for (int i = 2; i < words.size(); i += 2) {
        int y = Integer.parseInt(words.get(i + 1));
        switch (words.get(i)) {
          case "--max-y" -> maxY = OptionalInt.of(y);
          case "--min-y" -> minY = OptionalInt.of(y);
          default -> {
            return Optional.empty();
          }
        }
      }
      return Optional.of(new ZoneShape.Cylinder(at.blockX(), at.blockZ(), radius, minY, maxY));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** {@code box <x1> <y1> <z1> <x2> <y2> <z2>}. */
  private static Optional<ZoneShape> box(List<String> words) {
    if (words.size() != 7) {
      return Optional.empty();
    }
    try {
      int[] c = new int[6];
      for (int i = 0; i < 6; i++) {
        c[i] = Integer.parseInt(words.get(i + 1));
      }
      return Optional.of(ZoneShape.Box.between(c[0], c[1], c[2], c[3], c[4], c[5]));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  // Changing

  void mode(CommandSource source, Arguments arguments) {
    Optional<ZoneMode> mode = ZoneMode.of(arguments.string("mode"));
    if (mode.isEmpty()) {
      source.reply(messages().get("zone.bad-mode"));
      return;
    }
    change(
        source,
        arguments.string("id"),
        "mode",
        zone -> zone.mode().id(),
        zone -> zone.withMode(mode.get()),
        "zone.mode",
        mode.get());
  }

  void after(CommandSource source, Arguments arguments) {
    Optional<ZoneMode> mode = ZoneMode.of(arguments.string("mode"));
    if (mode.isEmpty() || (mode.get() != ZoneMode.OFF && mode.get() != ZoneMode.NO_BUILD)) {
      source.reply(messages().get("zone.bad-after"));
      return;
    }
    change(
        source,
        arguments.string("id"),
        "afterUnlock",
        zone -> zone.afterUnlock().id(),
        zone -> zone.withAfterUnlock(mode.get()),
        "zone.after",
        mode.get());
  }

  private void change(
      CommandSource source,
      String id,
      String key,
      java.util.function.Function<Zone, String> value,
      java.util.function.UnaryOperator<Zone> how,
      String doneKey,
      ZoneMode mode) {
    Optional<Zone> zone = zones.editingZone(id);
    if (zone.isEmpty()) {
      source.reply(messages().get("zone.unknown", "id", id));
      return;
    }
    int season = zones.editingSeason().orElseThrow();
    Zone changed = how.apply(zone.get());
    edit(
        source,
        season,
        new Change("zones." + id + "." + key, value.apply(zone.get()), value.apply(changed)),
        file -> SeasonFiles.writeZone(file, backups, season, changed),
        () -> source.reply(messages().get(doneKey, "id", id, "mode", mode.id())));
  }

  void remove(CommandSource source, Arguments arguments) {
    String id = arguments.string("id");
    Optional<Zone> zone = zones.editingZone(id);
    if (zone.isEmpty()) {
      source.reply(messages().get("zone.unknown", "id", id));
      return;
    }
    int season = zones.editingSeason().orElseThrow();
    edit(
        source,
        season,
        new Change("zones." + id, summary(zone.get()), NONE),
        file -> SeasonFiles.removeZone(file, backups, season, id),
        () -> source.reply(messages().get("zone.removed", "id", id)));
  }

  /**
   * The zones of the season being played, if they are in force, and of the season zone commands
   * edit, if that is another.
   */
  void list(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    OptionalInt editing = zones.editingSeason();
    if (editing.isEmpty()) {
      source.reply(messages.get("zone.no-season"));
      return;
    }
    int played = seasons.state().map(s -> s.season()).orElse(editing.getAsInt());
    if (played != editing.getAsInt() && zones.live(played)) {
      list(source, played);
    }
    list(source, editing.getAsInt());
  }

  private void list(CommandSource source, int season) {
    Messages messages = messages();
    String number = Integer.toString(season);
    Map<String, Zone> all = file(season).map(SeasonFile::zones).orElse(Map.of());
    if (all.isEmpty()) {
      source.reply(messages.get("zone.none", "season", number));
      return;
    }
    source.reply(messages.get("zone.header", "season", number));
    for (Zone zone : all.values()) {
      source.reply(
          messages.get(
              "zone.line",
              "id",
              zone.id(),
              "shape",
              describe(zone.shape()),
              "dimension",
              zone.dimension(),
              "mode",
              zone.mode().id(),
              "until",
              zones.until(zone),
              "after",
              zone.afterUnlock().id(),
              "now",
              zones.live(season) ? zones.now(zone.id()).id() : ZoneMode.OFF.id()));
    }
  }

  // Unlocking

  void unlock(CommandSource source, Arguments arguments) {
    String target = arguments.string("target");
    if (target.equals(Zone.HOMECOMING)) {
      Transition transition = zones.homecoming(actor(source));
      source.reply(
          transition instanceof Transition.Refused refused
              ? messages().get(refused.messageKey(), "season", season())
              : messages().get("homecoming.done"));
      return;
    }
    toggle(source, target, true);
  }

  void lock(CommandSource source, Arguments arguments) {
    toggle(source, arguments.string("zone"), false);
  }

  private void toggle(CommandSource source, String id, boolean open) {
    Messages messages = messages();
    switch (zones.set(id, open, actor(source))) {
      case DONE ->
          source.reply(
              messages.get(
                  open ? "zone.unlocked" : "zone.locked", "id", id, "mode", zones.now(id).id()));
      case ALREADY ->
          source.reply(
              messages.get(open ? "zone.already-unlocked" : "zone.already-locked", "id", id));
      case NOT_LIVE ->
          source.reply(
              messages.get(
                  "zone.not-live",
                  "id",
                  id,
                  "season",
                  Integer.toString(zones.editingSeason().orElse(0))));
      case UNKNOWN -> source.reply(messages.get("zone.unknown", "id", id));
    }
  }

  // Helpers

  private interface FileEdit {
    void apply(Path file) throws java.io.IOException;
  }

  /** What an edit changes, for the audit log: a key path in the season file, and its values. */
  private record Change(String path, String before, String after) {}

  /** The value of a key that isn't there, before an add or after a remove. */
  private static final String NONE = ConfigEdits.NONE;

  /** Writes a season file, then loads every file again and swaps them in, and audits the change. */
  private void edit(CommandSource source, int season, Change change, FileEdit edit, Runnable done) {
    Path file = loader.seasonFile(season);
    edits.edit(
        source,
        new ConfigEdits.Change(
            "seasons/" + season + ".yml " + change.path(), change.before(), change.after()),
        () -> edit.apply(file),
        "zone.failed",
        done);
  }

  /** Who ran a command, as the audit log names them. */
  private String actor(CommandSource source) {
    return edits.actor(source);
  }

  /** A zone in one line, for the audit log. */
  private String summary(Zone zone) {
    return describe(zone.shape())
        + " in "
        + zone.dimension()
        + ", "
        + zone.mode().id()
        + " until unlocked, then "
        + zone.afterUnlock().id();
  }

  private Optional<SeasonFile> file(int season) {
    return config.get().season(season);
  }

  /** A shape as admins read it: "a cylinder of radius 12 around 200, 200 up to y 80". */
  private String describe(ZoneShape shape) {
    Messages messages = messages();
    return switch (shape) {
      case ZoneShape.Cylinder c -> {
        StringBuilder out =
            new StringBuilder(
                messages.text(
                    "zone.shape.cylinder",
                    "radius",
                    number(c.radius()),
                    "x",
                    number(c.centerX()),
                    "z",
                    number(c.centerZ())));
        c.minY()
            .ifPresent(
                y -> out.append(messages.text("zone.shape.min-y", "y", Integer.toString(y))));
        c.maxY()
            .ifPresent(
                y -> out.append(messages.text("zone.shape.max-y", "y", Integer.toString(y))));
        yield out.toString();
      }
      case ZoneShape.Box b ->
          messages.text(
              "zone.shape.box",
              "x1",
              Integer.toString(b.minX()),
              "y1",
              Integer.toString(b.minY()),
              "z1",
              Integer.toString(b.minZ()),
              "x2",
              Integer.toString(b.maxX()),
              "y2",
              Integer.toString(b.maxY()),
              "z2",
              Integer.toString(b.maxZ()));
    };
  }

  private static String number(double value) {
    return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
  }

  private String season() {
    return seasons.state().map(s -> Integer.toString(s.season())).orElse("?");
  }

  private Messages messages() {
    return config.get().messages();
  }
}
