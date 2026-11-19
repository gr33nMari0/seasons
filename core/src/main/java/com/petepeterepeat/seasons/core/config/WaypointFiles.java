// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.petepeterepeat.seasons.core.map.Waypoint;
import com.petepeterepeat.seasons.core.map.Waypoints;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.store.AtomicFiles;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Reads and writes {@code <world>/seasons/markers.yml}, the shared waypoints. Commands and hand
 * edits share the format, so a hand edit takes effect with {@code /season reload}. Each waypoint is
 * written as a spawn is.
 *
 * <pre>
 * schema-version: 1
 * sets:
 *   megaprojects:
 *     Peter's Tower: { dimension: minecraft:overworld, pos: [100, 64, -200] }
 * </pre>
 */
public final class WaypointFiles {
  private WaypointFiles() {}

  /** A value that doesn't make sense, at a key path such as {@code sets.hubs.Spawn}. */
  public static final class Invalid extends Exception {
    private static final long serialVersionUID = 1L;
    private final String path;

    Invalid(String path, String message) {
      super(message);
      this.path = path;
    }

    public String path() {
      return path;
    }
  }

  /** The waypoints from the file's root node. */
  public static Waypoints read(ConfigurationNode node, Predicate<String> dimensionExists)
      throws Invalid {
    Map<String, List<Waypoint>> sets = new TreeMap<>();
    for (Map.Entry<Object, ? extends ConfigurationNode> set :
        node.node("sets").childrenMap().entrySet()) {
      String id = String.valueOf(set.getKey());
      String setPath = "sets." + id;
      if (!Waypoints.SET.matcher(id).matches()) {
        throw new Invalid(setPath, "set names are lower-case letters, digits and dashes");
      }
      List<Waypoint> waypoints = new ArrayList<>();
      Set<String> names = new HashSet<>();
      for (Map.Entry<Object, ? extends ConfigurationNode> waypoint :
          set.getValue().childrenMap().entrySet()) {
        String name = String.valueOf(waypoint.getKey());
        String path = setPath + "." + name;
        if (!Waypoints.validName(name)) {
          throw new Invalid(
              path, "a waypoint's name is 1 to " + Waypoints.MAX_NAME + " characters");
        }
        if (!names.add(Waypoints.key(name))) {
          throw new Invalid(path, "the set already has a waypoint by that name, ignoring case");
        }
        Optional<Position> at;
        try {
          at = ConfigLoader.spawn(waypoint.getValue());
        } catch (IllegalArgumentException e) {
          throw new Invalid(path, String.valueOf(e.getMessage()));
        }
        if (at.isEmpty()) {
          throw new Invalid(path, "pos needs three numbers, [x, y, z]");
        }
        if (!dimensionExists.test(at.get().dimension())) {
          throw new Invalid(
              path + ".dimension", "this server has no dimension " + at.get().dimension());
        }
        waypoints.add(new Waypoint(name, at.get()));
      }
      if (!waypoints.isEmpty()) {
        sets.put(id, waypoints);
      }
    }
    return new Waypoints(sets);
  }

  /** Adds a waypoint to a set, making the file or the set if need be. */
  public static void add(Path file, Path backupDir, String set, Waypoint waypoint)
      throws IOException {
    edit(
        file,
        backupDir,
        node -> SeasonFiles.writePosition(node.node("sets", set, waypoint.name()), waypoint.at()));
  }

  /** Removes the waypoint with this exact name from a set, and the set once it is empty. */
  public static void remove(Path file, Path backupDir, String set, String name) throws IOException {
    edit(
        file,
        backupDir,
        node -> {
          ConfigurationNode waypoints = node.node("sets", set);
          waypoints.removeChild(name);
          if (waypoints.empty()) {
            node.node("sets").removeChild(set);
          }
        });
  }

  private interface Edit {
    void apply(ConfigurationNode node) throws IOException;
  }

  private static void edit(Path file, Path backupDir, Edit edit) throws IOException {
    ConfigurationNode node =
        Files.exists(file)
            ? YamlConfigurationLoader.builder().path(file).build().load()
            : YamlConfigurationLoader.builder().build().createNode();
    node.node("schema-version").set(ConfigLoader.SCHEMA_VERSION);
    edit.apply(node);
    StringWriter out = new StringWriter();
    YamlConfigurationLoader.builder()
        .nodeStyle(NodeStyle.BLOCK)
        .sink(() -> new BufferedWriter(out))
        .build()
        .save(node);
    AtomicFiles.writeKeepingBackups(file, out.toString().getBytes(UTF_8), backupDir);
  }
}
