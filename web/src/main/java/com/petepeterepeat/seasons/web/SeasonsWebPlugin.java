// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import com.petepeterepeat.seasons.api.SeasonsApi;
import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/** The Seasons Web plugin's entry point. The web server itself arrives in M7. */
public final class SeasonsWebPlugin extends JavaPlugin {
  /**
   * Seasons Web's plugin id on bstats.org ("SeasonsMC Web"). It sends only what bStats always does;
   * whether Seasons Web is installed is one of Seasons' own charts.
   */
  static final int BSTATS_PLUGIN_ID = 35500;

  private @Nullable Metrics metrics;

  @Override
  public void onEnable() {
    RegisteredServiceProvider<SeasonsApi> provider =
        getServer().getServicesManager().getRegistration(SeasonsApi.class);
    if (provider == null) {
      getSLF4JLogger().error("Seasons API is not registered; disabling Seasons Web");
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    getSLF4JLogger().info("Found Seasons {}", provider.getProvider().version());
    metrics = new Metrics(this, BSTATS_PLUGIN_ID);
  }

  @Override
  public void onDisable() {
    Metrics running = metrics;
    if (running != null) {
      running.shutdown();
      metrics = null;
    }
  }
}
