// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.port.Displays;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;

final class PaperDisplays implements Displays {
  private final Server server;
  private final PaperText text;
  private final NamespacedKey tagKey;

  PaperDisplays(Server server, PaperText text, NamespacedKey tagKey) {
    this.server = server;
    this.text = text;
    this.tagKey = tagKey;
  }

  @Override
  public UUID spawnText(Position position, Message message, String tag) {
    var location = PaperPositions.toLocation(position);
    TextDisplay display =
        location
            .getWorld()
            .spawn(
                location,
                TextDisplay.class,
                spawned -> {
                  spawned.text(text.render(message));
                  spawned.setPersistent(true);
                  spawned.getPersistentDataContainer().set(tagKey, PersistentDataType.STRING, tag);
                });
    return display.getUniqueId();
  }

  @Override
  public void remove(UUID id) {
    Optional.ofNullable(server.getEntity(id))
        .filter(entity -> entity.getPersistentDataContainer().has(tagKey))
        .ifPresent(Entity::remove);
  }
}
