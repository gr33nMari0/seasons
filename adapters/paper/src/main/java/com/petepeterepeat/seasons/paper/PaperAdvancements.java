// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.port.Advancements;
import com.petepeterepeat.seasons.core.port.PlayerId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Server;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;

final class PaperAdvancements implements Advancements {
  private final Server server;
  private final PaperPlayers players;

  PaperAdvancements(Server server, PaperPlayers players) {
    this.server = server;
    this.players = players;
  }

  @Override
  public List<String> visible() {
    List<String> ids = new ArrayList<>();
    for (Advancement advancement : visibleAdvancements()) {
      ids.add(advancement.getKey().asString());
    }
    return ids;
  }

  @Override
  public Map<String, Set<String>> awarded(PlayerId id) {
    Map<String, Set<String>> awarded = new HashMap<>();
    players
        .player(id)
        .ifPresent(player -> {
          for (Advancement advancement : visibleAdvancements()) {
            Set<String> criteria =
                progress(player, advancement).getAwardedCriteria().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!criteria.isEmpty()) {
              awarded.put(advancement.getKey().asString(), criteria);
            }
          }
        });
    return awarded;
  }

  private static AdvancementProgress progress(Player player, Advancement advancement) {
    return player.getAdvancementProgress(advancement);
  }

  /** Visible means it has a display; recipe unlocks have none. */
  private List<Advancement> visibleAdvancements() {
    List<Advancement> visible = new ArrayList<>();
    for (Iterator<Advancement> it = server.advancementIterator(); it.hasNext(); ) {
      Advancement advancement = it.next();
      if (advancement.getDisplay() != null) {
        visible.add(advancement);
      }
    }
    return visible;
  }
}
