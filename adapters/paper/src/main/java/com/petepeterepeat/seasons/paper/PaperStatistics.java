// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Statistics;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Statistic;

final class PaperStatistics implements Statistics {
  private final PaperPlayers players;

  PaperStatistics(PaperPlayers players) {
    this.players = players;
  }

  /** The General tab holds the untyped statistics. */
  @Override
  public Map<String, Long> general(PlayerId id) {
    Map<String, Long> values = new HashMap<>();
    players
        .player(id)
        .ifPresent(player -> {
          for (Statistic statistic : Statistic.values()) {
            if (statistic.getType() == Statistic.Type.UNTYPED) {
              values.put(statistic.getKey().asString(), (long) player.getStatistic(statistic));
            }
          }
        });
    return values;
  }
}
