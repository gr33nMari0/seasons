// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import com.petepeterepeat.seasons.api.SeasonsApi;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/** The Seasons Web plugin's entry point. The web server itself arrives in M7. */
public final class SeasonsWebPlugin extends JavaPlugin {
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
  }
}
