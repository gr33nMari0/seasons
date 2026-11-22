// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.map;

import com.petepeterepeat.seasons.core.command.ArgumentType;
import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.command.Positions;
import com.petepeterepeat.seasons.core.config.ConfigEdits;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.config.ConfigLoader;
import com.petepeterepeat.seasons.core.config.WaypointFiles;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.Position;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /season marker add <set> <name>} and {@code remove <set> <name>}: shared waypoints, at the
 * admin's position, in markers.yml. Edits go through {@link ConfigEdits}, so they are on the map at
 * once and audited. Names are plain text and told apart ignoring case.
 */
public final class MarkerCommands {
  /** Permission for the marker commands, with the other config commands. */
  public static final String PERMISSION = "seasons.admin.config";

  private final Platform platform;
  private final ConfigHolder config;
  private final ConfigLoader loader;
  private final ConfigEdits edits;
  private final Path backups;
  private final Supplier<MapPublisher.Status> map;

  /**
   * @param backups {@code <world>/seasons/backups}
   * @param map where publishing stands, so an admin hears when nothing will show the waypoint
   */
  public MarkerCommands(
      Platform platform,
      ConfigHolder config,
      ConfigLoader loader,
      ConfigEdits edits,
      Path backups,
      Supplier<MapPublisher.Status> map) {
    this.platform = platform;
    this.config = config;
    this.loader = loader;
    this.edits = edits;
    this.backups = backups;
    this.map = map;
  }

  /** The {@code marker} node under {@code /season}. */
  public CommandNode node() {
    return CommandNode.literal("marker")
        .permission(PERMISSION)
        .then(
            CommandNode.literal("add")
                .then(
                    CommandNode.argument("set", ArgumentType.word())
                        .then(
                            CommandNode.argument("name", ArgumentType.greedy())
                                .executes(this::add))))
        .then(
            CommandNode.literal("move")
                .then(
                    CommandNode.argument("set", ArgumentType.word())
                        .then(
                            CommandNode.argument("name", ArgumentType.greedy())
                                .executes(this::move))))
        .then(
            CommandNode.literal("remove")
                .then(
                    CommandNode.argument("set", ArgumentType.word())
                        .then(
                            CommandNode.argument("name", ArgumentType.greedy())
                                .executes(this::remove))))
        .then(
            CommandNode.literal("list")
                .executes(this::list)
                .then(CommandNode.argument("set", ArgumentType.word()).executes(this::list)))
        .build();
  }

  /** The flag that puts a typed position after a waypoint's name (questions 264). */
  static final String AT = " --at ";

  /** A waypoint's name and, after {@code --at}, where it goes. */
  private record Named(String name, Optional<String> at) {
    static Named of(String text) {
      String padded = " " + text.strip() + " ";
      int at = padded.lastIndexOf(AT);
      if (at < 0) {
        return new Named(text.strip(), Optional.empty());
      }
      return new Named(
          padded.substring(0, at).strip(), Optional.of(padded.substring(at + AT.length()).strip()));
    }
  }

  /** Where the waypoint goes: typed after --at, or where the sender stands; empty if neither. */
  private Optional<Position> where(CommandSource source, Optional<String> typed) {
    Optional<Position> origin = source.player().map(p -> platform.players().position(p));
    if (typed.isEmpty()) {
      if (origin.isEmpty()) {
        source.reply(messages().get("marker.player-only"));
      }
      return origin;
    }
    String dimension = origin.map(Position::dimension).orElse("minecraft:overworld");
    Positions.Read read = Positions.typed(typed.get(), origin, dimension, Positions.facing(origin));
    if (read instanceof Positions.Read.Block block) {
      return Optional.of(block.block());
    }
    source.reply(messages().get(((Positions.Read.Failed) read).messageKey()));
    return Optional.empty();
  }

  void add(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    String set = arguments.string("set");
    Named named = Named.of(arguments.string("name"));
    String name = named.name();
    if (leftOut(source)) {
      return;
    }
    if (!Waypoints.SET.matcher(set).matches()) {
      source.reply(messages.get("marker.bad-set"));
      return;
    }
    if (!Waypoints.validName(name)) {
      source.reply(messages.get("marker.bad-name"));
      return;
    }
    Optional<Waypoint> existing = config.get().waypoints().find(set, name);
    if (existing.isPresent()) {
      source.reply(messages.get("marker.exists", "set", set, "name", existing.get().name()));
      return;
    }
    Optional<Position> found = where(source, named.at());
    if (found.isEmpty()) {
      return;
    }
    Position at = found.get();
    edits.edit(
        source,
        new ConfigEdits.Change(target(set, name), ConfigEdits.NONE, ConfigEdits.describe(at)),
        loader.markersFile(),
        () -> WaypointFiles.add(loader.markersFile(), backups, set, new Waypoint(name, at)),
        "marker.failed",
        () -> {
          source.reply(
              messages()
                  .get(
                      "marker.added",
                      "name",
                      name,
                      "set",
                      set,
                      "x",
                      Integer.toString(at.blockX()),
                      "y",
                      Integer.toString(at.blockY()),
                      "z",
                      Integer.toString(at.blockZ()),
                      "dimension",
                      at.dimension()));
          if (map.get() instanceof MapPublisher.Status.NotInstalled) {
            source.reply(messages().get("marker.no-map"));
          }
        });
  }

  /** {@code marker move <set> <name> [--at <position>]}. */
  void move(CommandSource source, Arguments arguments) {
    String set = arguments.string("set");
    Named named = Named.of(arguments.string("name"));
    if (leftOut(source)) {
      return;
    }
    Optional<Waypoint> existing = config.get().waypoints().find(set, named.name());
    if (existing.isEmpty()) {
      source.reply(messages().get("marker.unknown", "set", set, "name", named.name()));
      return;
    }
    Optional<Position> found = where(source, named.at());
    if (found.isEmpty()) {
      return;
    }
    Waypoint waypoint = existing.get();
    Position at = found.get();
    edits.edit(
        source,
        new ConfigEdits.Change(
            target(set, waypoint.name()),
            ConfigEdits.describe(waypoint.at()),
            ConfigEdits.describe(at)),
        loader.markersFile(),
        () ->
            WaypointFiles.add(
                loader.markersFile(), backups, set, new Waypoint(waypoint.name(), at)),
        "marker.failed",
        () ->
            source.reply(
                messages()
                    .get(
                        "marker.moved",
                        "name",
                        waypoint.name(),
                        "set",
                        set,
                        "x",
                        Integer.toString(at.blockX()),
                        "y",
                        Integer.toString(at.blockY()),
                        "z",
                        Integer.toString(at.blockZ()),
                        "dimension",
                        at.dimension())));
  }

  /** {@code marker list [set]}: every waypoint, or one set's. */
  void list(CommandSource source, Arguments arguments) {
    Optional<String> only = arguments.optionalString("set");
    var sets = config.get().waypoints().sets();
    boolean any = false;
    for (var entry : new java.util.TreeMap<>(sets).entrySet()) {
      if (only.isPresent() && !only.get().equals(entry.getKey())) {
        continue;
      }
      for (Waypoint waypoint : entry.getValue()) {
        any = true;
        Position at = waypoint.at();
        source.reply(
            messages()
                .get(
                    "marker.line",
                    "set",
                    entry.getKey(),
                    "name",
                    waypoint.name(),
                    "x",
                    Integer.toString(at.blockX()),
                    "y",
                    Integer.toString(at.blockY()),
                    "z",
                    Integer.toString(at.blockZ()),
                    "dimension",
                    at.dimension()));
      }
    }
    if (!any) {
      source.reply(messages().get("marker.none"));
    }
  }

  void remove(CommandSource source, Arguments arguments) {
    String set = arguments.string("set");
    String name = arguments.string("name").strip();
    if (leftOut(source)) {
      return;
    }
    Optional<Waypoint> found = config.get().waypoints().find(set, name);
    if (found.isEmpty()) {
      source.reply(messages().get("marker.unknown", "set", set, "name", name));
      return;
    }
    Waypoint waypoint = found.get();
    edits.edit(
        source,
        new ConfigEdits.Change(
            target(set, waypoint.name()), ConfigEdits.describe(waypoint.at()), ConfigEdits.NONE),
        loader.markersFile(),
        () -> WaypointFiles.remove(loader.markersFile(), backups, set, waypoint.name()),
        "marker.failed",
        () -> source.reply(messages().get("marker.removed", "set", set, "name", waypoint.name())));
  }

  /**
   * Refuses an edit while markers.yml is left out after failing to load at startup: the waypoints
   * aren't known, and the file is the admin's to fix.
   */
  private boolean leftOut(CommandSource source) {
    if (config.get().markersLeftOut().isEmpty()) {
      return false;
    }
    source.reply(messages().get("marker.left-out"));
    return true;
  }

  /** A waypoint as the audit log names it. */
  private static String target(String set, String name) {
    return ConfigLoader.MARKERS + " sets." + set + "." + name;
  }

  private Messages messages() {
    return config.get().messages();
  }
}
