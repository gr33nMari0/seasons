// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.map;

import com.petepeterepeat.seasons.core.command.ArgumentType;
import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.CommandSource;
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
            CommandNode.literal("remove")
                .then(
                    CommandNode.argument("set", ArgumentType.word())
                        .then(
                            CommandNode.argument("name", ArgumentType.greedy())
                                .executes(this::remove))))
        .build();
  }

  void add(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    String set = arguments.string("set");
    String name = arguments.string("name").strip();
    if (source.player().isEmpty()) {
      source.reply(messages.get("marker.player-only"));
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
    Position at = platform.players().position(source.player().get());
    edits.edit(
        source,
        new ConfigEdits.Change(target(set, name), ConfigEdits.NONE, ConfigEdits.describe(at)),
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

  void remove(CommandSource source, Arguments arguments) {
    String set = arguments.string("set");
    String name = arguments.string("name").strip();
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
        () -> WaypointFiles.remove(loader.markersFile(), backups, set, waypoint.name()),
        "marker.failed",
        () -> source.reply(messages().get("marker.removed", "set", set, "name", waypoint.name())));
  }

  /** A waypoint as the audit log names it. */
  private static String target(String set, String name) {
    return ConfigLoader.MARKERS + " sets." + set + "." + name;
  }

  private Messages messages() {
    return config.get().messages();
  }
}
