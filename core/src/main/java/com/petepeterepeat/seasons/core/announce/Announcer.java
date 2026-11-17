// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.announce;

import com.petepeterepeat.seasons.core.config.ConfigSnapshot;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Permissions;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Players;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Says things to everyone, to admins, and on Discord, with text from messages.yml. */
public final class Announcer {
  private static final Logger LOG = LoggerFactory.getLogger(Announcer.class);

  private final Players players;
  private final Permissions permissions;
  private final Webhooks webhooks;
  private final Supplier<ConfigSnapshot> config;

  public Announcer(
      Players players,
      Permissions permissions,
      Webhooks webhooks,
      Supplier<ConfigSnapshot> config) {
    this.players = players;
    this.permissions = permissions;
    this.webhooks = webhooks;
    this.config = config;
  }

  /** In chat, to everyone online and the console. */
  public void broadcast(String key, String... placeholders) {
    players.broadcast(messages().get(key, placeholders));
  }

  /** On the public Discord channel, if a webhook is set. */
  public void discord(String key, String... placeholders) {
    ConfigSnapshot snapshot = config.get();
    snapshot
        .secrets()
        .discordWebhook()
        .ifPresent(url -> webhooks.post(url, snapshot.messages().text(key, placeholders)));
  }

  /** To online players holding {@code permission}, and the server log. */
  public void admins(String permission, String key, String... placeholders) {
    LOG.warn("{}", messages().text(key, placeholders));
    for (PlayerRef player : players.online()) {
      if (permissions.has(player.id(), permission)) {
        players.sendMessage(player.id(), messages().get(key, placeholders));
      }
    }
  }

  private Messages messages() {
    return config.get().messages();
  }
}
