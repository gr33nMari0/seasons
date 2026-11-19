// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.testkit;

import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.ExtrudeMarker;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

/**
 * {@code /scenario bluemap}: what BlueMap holds in Seasons' marker sets (those whose id starts with
 * {@code seasons}), read through BlueMap's own API, so a scenario sees what BlueMap would serve.
 * Only loaded when BlueMap is installed (docs/adr/0004).
 *
 * <p>One line per set, {@code BMSET|dimension|map|set|label}, and per marker, {@code
 * BM|dimension|map|set|id|type|x y z|shape|label}, where shape is {@code minY..maxY points holes}
 * for an area and {@code -} for a point; then {@code BM done <maps> api@<instance>}, the instance
 * telling one start of BlueMap's API from the next. {@code BM off} while the API isn't enabled.
 */
final class ScenarioBlueMap {
  static final String OFF = "BM off";

  private ScenarioBlueMap() {}

  static void print(CommandSender sender, Server server) {
    Optional<BlueMapAPI> found = BlueMapAPI.getInstance();
    if (found.isEmpty()) {
      sender.sendMessage(OFF);
      return;
    }
    BlueMapAPI api = found.get();
    int maps = 0;
    for (World world : server.getWorlds()) {
      Optional<BlueMapWorld> bluemap = api.getWorld(world);
      if (bluemap.isEmpty()) {
        continue;
      }
      String dimension = world.getKey().asString();
      for (BlueMapMap map : bluemap.get().getMaps()) {
        maps++;
        for (Map.Entry<String, MarkerSet> set : new TreeMap<>(map.getMarkerSets()).entrySet()) {
          if (!set.getKey().startsWith("seasons")) {
            continue;
          }
          sender.sendMessage(
              String.join(
                  "|", "BMSET", dimension, map.getId(), set.getKey(), set.getValue().getLabel()));
          for (Map.Entry<String, Marker> marker :
              new TreeMap<>(set.getValue().getMarkers()).entrySet()) {
            sender.sendMessage(
                line(dimension, map.getId(), set.getKey(), marker.getKey(), marker.getValue()));
          }
        }
      }
    }
    sender.sendMessage(
        "BM done " + maps + " api@" + Integer.toHexString(System.identityHashCode(api)));
  }

  private static String line(String dimension, String map, String set, String id, Marker marker) {
    Vector3d at = marker.getPosition();
    String shape =
        switch (marker) {
          case ExtrudeMarker area ->
              number(area.getShapeMinY())
                  + ".."
                  + number(area.getShapeMaxY())
                  + " "
                  + area.getShape().getPointCount()
                  + " "
                  + area.getHoles().size();
          case ShapeMarker area ->
              number(area.getShapeY())
                  + ".."
                  + number(area.getShapeY())
                  + " "
                  + area.getShape().getPointCount()
                  + " "
                  + area.getHoles().size();
          default -> "-";
        };
    return String.join(
        "|",
        "BM",
        dimension,
        map,
        set,
        id,
        marker.getType(),
        number(at.getX()) + " " + number(at.getY()) + " " + number(at.getZ()),
        shape,
        marker.getLabel());
  }

  private static String number(double value) {
    return String.format(Locale.ROOT, "%.1f", value);
  }
}
