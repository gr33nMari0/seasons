// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Players;
import java.util.Collection;
import java.util.Optional;
import org.bukkit.Server;
import org.bukkit.entity.Player;

final class PaperPlayers implements Players {
  private final Server server;
  private final PaperText text;

  PaperPlayers(Server server, PaperText text) {
    this.server = server;
    this.text = text;
  }

  @Override
  public Collection<PlayerRef> online() {
    return server.getOnlinePlayers().stream().map(PaperPlayers::ref).toList();
  }

  @Override
  public Optional<PlayerRef> find(PlayerId id) {
    return player(id).map(PaperPlayers::ref);
  }

  @Override
  public void sendMessage(PlayerId id, Message message) {
    player(id).ifPresent(p -> p.sendMessage(text.render(message)));
  }

  Optional<Player> player(PlayerId id) {
    return Optional.ofNullable(server.getPlayer(id.uuid()));
  }

  static PlayerRef ref(Player player) {
    return new PlayerRef(new PlayerId(player.getUniqueId()), player.getName());
  }
}
