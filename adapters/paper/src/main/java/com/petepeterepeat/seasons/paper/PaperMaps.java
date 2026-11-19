// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.map.MapPublisher;
import com.petepeterepeat.seasons.integrations.bluemap.BlueMapPublisher;
import org.bukkit.Server;
import org.slf4j.Logger;

/**
 * The web map Seasons publishes to: BlueMap when it is installed, else none. BlueMap's classes are
 * on Seasons' classpath only when it is installed ({@code paper-plugin.yml} joins its classpath),
 * so nothing touches them unless it is.
 */
final class PaperMaps {
  private PaperMaps() {}

  static MapPublisher publisher(Server server, Logger log) {
    if (server.getPluginManager().getPlugin("BlueMap") == null) {
      return MapPublisher.NONE;
    }
    try {
      return WithBlueMap.publisher();
    } catch (LinkageError e) {
      log.warn(
          "BlueMap is installed, but not a version whose API Seasons can use ({}), so no map"
              + " markers are published",
          e.toString());
      return MapPublisher.NONE;
    }
  }

  /** Loaded only once BlueMap is known to be installed. */
  private static final class WithBlueMap {
    static MapPublisher publisher() {
      // BlueMap finds a world by Paper's own World.
      return new BlueMapPublisher(dimension -> PaperPositions.world(dimension).map(w -> w));
    }
  }
}
