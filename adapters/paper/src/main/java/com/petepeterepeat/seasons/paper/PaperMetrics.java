// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.Seasons;
import com.petepeterepeat.seasons.core.usage.UsageCharts;
import java.util.Optional;
import java.util.function.Supplier;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Seasons' bStats metrics: what bStats always sends (server count, game version, server software,
 * Java version) and Seasons' own anonymous charts ({@link UsageCharts}). bStats is shaded and
 * relocated into Seasons' package. It asks for each chart on the server thread, and sends nothing
 * when it is turned off in {@code plugins/bStats/config.yml}.
 */
final class PaperMetrics {
  /** Seasons' plugin id on bstats.org ("SeasonsMC"). */
  static final int PLUGIN_ID = 35499;

  private final Metrics metrics;

  /**
   * @param seasons the running Seasons, empty while it restarts
   */
  PaperMetrics(JavaPlugin plugin, Supplier<Optional<Seasons>> seasons) {
    metrics = new Metrics(plugin, PLUGIN_ID);
    for (String chart : UsageCharts.CHARTS) {
      metrics.addCustomChart(
          new SimplePie(
              chart,
              () ->
                  seasons
                      .get()
                      .map(running ->
                          running
                              .usage(
                                  plugin
                                      .getServer()
                                      .getPluginManager()
                                      .isPluginEnabled("SeasonsWeb"))
                              .get(chart))
                      .orElse(null)));
    }
  }

  void stop() {
    metrics.shutdown();
  }
}
