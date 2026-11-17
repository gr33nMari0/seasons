// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.port.Permissions;
import com.petepeterepeat.seasons.core.port.PlayerId;

final class PaperPermissions implements Permissions {
  private final PaperPlayers players;

  PaperPermissions(PaperPlayers players) {
    this.players = players;
  }

  @Override
  public boolean has(PlayerId id, String node) {
    return players.player(id).map(player -> player.hasPermission(node)).orElse(false);
  }
}
