// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.announce;

import com.petepeterepeat.seasons.core.config.ConfigSnapshot;
import com.petepeterepeat.seasons.core.log.DebugArea;
import com.petepeterepeat.seasons.core.log.SeasonsLog;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Permissions;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Players;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;

/** Says things to everyone, to admins, and on Discord, with text from messages.yml. */
public final class Announcer {
  private static final Logger LOG = SeasonsLog.get();

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

  /** Lines in chat, to everyone online and the console. */
  public void broadcast(List<Message> lines) {
    lines.forEach(players::broadcast);
  }

  /** Lines as one post on the public Discord channel, if a webhook is set. */
  public void discord(List<Message> lines) {
    ConfigSnapshot snapshot = config.get();
    snapshot
        .secrets()
        .discordWebhook()
        .ifPresent(url ->
            webhooks.post(url, String.join("\n", lines.stream().map(Messages::plain).toList())));
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

  /**
   * To online players holding {@code permission}, for their information only: the server log gets
   * it as a debug line for {@code area}, since nothing needs doing (questions 295).
   */
  public void adminsNote(DebugArea area, String permission, String key, String... placeholders) {
    SeasonsLog.debug(area, "{}", messages().text(key, placeholders));
    for (PlayerRef player : players.online()) {
      if (permissions.has(player.id(), permission)) {
        players.sendMessage(player.id(), messages().get(key, placeholders));
      }
    }
  }

  /**
   * Several lines to online players holding {@code permission}, except {@code except} (who has
   * already been told), and the server log.
   */
  public void admins(String permission, Optional<PlayerId> except, List<Message> lines) {
    lines.forEach(line -> LOG.warn("{}", Messages.plain(line)));
    for (PlayerRef player : players.online()) {
      if (!except.equals(Optional.of(player.id())) && permissions.has(player.id(), permission)) {
        lines.forEach(line -> players.sendMessage(player.id(), line));
      }
    }
  }

  private Messages messages() {
    return config.get().messages();
  }
}
