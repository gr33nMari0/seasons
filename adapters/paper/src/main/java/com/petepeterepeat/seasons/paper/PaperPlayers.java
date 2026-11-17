// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Players;
import com.petepeterepeat.seasons.core.port.ProgressBar;
import java.util.Collection;
import java.util.Optional;
import net.kyori.adventure.bossbar.BossBar;
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

  @Override
  public void broadcast(Message message) {
    server.broadcast(text.render(message));
  }

  @Override
  public ProgressBar showProgressBar(Message title, float progress) {
    BossBar bar =
        BossBar.bossBar(
            text.render(title), clamp(progress), BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
    server.showBossBar(bar);
    return new ProgressBar() {
      @Override
      public void update(Message next, float nextProgress) {
        bar.name(text.render(next));
        bar.progress(clamp(nextProgress));
        // Showing again reaches players who joined since; those already viewing are unaffected.
        server.showBossBar(bar);
      }

      @Override
      public void hide() {
        server.hideBossBar(bar);
      }
    };
  }

  private static float clamp(float progress) {
    return Math.clamp(progress, BossBar.MIN_PROGRESS, BossBar.MAX_PROGRESS);
  }

  Optional<Player> player(PlayerId id) {
    return Optional.ofNullable(server.getPlayer(id.uuid()));
  }

  static PlayerRef ref(Player player) {
    return new PlayerRef(new PlayerId(player.getUniqueId()), player.getName());
  }
}
