// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.port.Storage;
import java.nio.file.Path;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

final class PaperStorage implements Storage {
  private final Plugin plugin;
  private final Server server;

  PaperStorage(Plugin plugin, Server server) {
    this.plugin = plugin;
    this.server = server;
  }

  @Override
  public Path configDirectory() {
    return plugin.getDataPath();
  }

  @Override
  public Path worldDataDirectory() {
    return server.getWorlds().getFirst().getWorldFolder().toPath().resolve("seasons");
  }
}
