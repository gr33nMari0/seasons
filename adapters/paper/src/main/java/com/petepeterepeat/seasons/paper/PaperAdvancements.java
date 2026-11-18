// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.port.AdvancementSnapshot;
import com.petepeterepeat.seasons.core.port.Advancements;
import com.petepeterepeat.seasons.core.port.Milestones;
import com.petepeterepeat.seasons.core.port.PlayerId;
import io.papermc.paper.advancement.AdvancementDisplay;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.jspecify.annotations.Nullable;

/**
 * Advancements, including the hidden ones without a display. Recipe unlocks ({@code
 * minecraft:recipes/...}) are left alone unless recipes reset too.
 */
final class PaperAdvancements implements Advancements, Listener {
  private static final String RECIPES = "recipes/";

  private final Server server;
  private final PaperPlayers players;
  private final Set<UUID> restoring = new HashSet<>();
  private @Nullable Milestones milestones;

  PaperAdvancements(Server server, PaperPlayers players) {
    this.server = server;
    this.players = players;
  }

  @Override
  public List<String> visible() {
    List<String> ids = new ArrayList<>();
    for (Advancement advancement : all()) {
      if (counted(advancement)) {
        ids.add(advancement.getKey().asString());
      }
    }
    return ids;
  }

  @Override
  public Map<String, Set<String>> awarded(PlayerId id) {
    Map<String, Set<String>> awarded = new HashMap<>();
    players
        .player(id)
        .ifPresent(player -> {
          for (Advancement advancement : all()) {
            if (advancement.getDisplay() == null) {
              continue;
            }
            Set<String> criteria =
                progress(player, advancement).getAwardedCriteria().stream()
                    .collect(Collectors.toUnmodifiableSet());
            if (!criteria.isEmpty()) {
              awarded.put(advancement.getKey().asString(), criteria);
            }
          }
        });
    return awarded;
  }

  @Override
  public AdvancementSnapshot snapshot(PlayerId id, boolean includeRecipes) {
    Player player = online(id);
    Map<String, Map<String, Instant>> awarded = new LinkedHashMap<>();
    int completed = 0;
    int visible = 0;
    for (Advancement advancement : all()) {
      boolean shown = counted(advancement);
      AdvancementProgress progress = progress(player, advancement);
      if (shown) {
        visible++;
        if (progress.isDone()) {
          completed++;
        }
      }
      if (!includeRecipes && isRecipe(advancement)) {
        continue;
      }
      Map<String, Instant> criteria = new LinkedHashMap<>();
      for (String criterion : progress.getAwardedCriteria()) {
        Date at = progress.getDateAwarded(criterion);
        criteria.put(criterion, at == null ? Instant.EPOCH : at.toInstant());
      }
      if (!criteria.isEmpty()) {
        awarded.put(advancement.getKey().asString(), criteria);
      }
    }
    return new AdvancementSnapshot(awarded, completed, visible);
  }

  @Override
  public void revokeAll(PlayerId id, boolean includeRecipes) {
    Player player = online(id);
    for (Advancement advancement : all()) {
      if (!includeRecipes && isRecipe(advancement)) {
        continue;
      }
      AdvancementProgress progress = progress(player, advancement);
      for (String criterion : List.copyOf(progress.getAwardedCriteria())) {
        progress.revokeCriteria(criterion);
      }
    }
  }

  @Override
  public void award(PlayerId id, Map<String, Map<String, Instant>> criteria) {
    Player player = online(id);
    restoring.add(player.getUniqueId());
    try {
      criteria.forEach((key, awarded) ->
          Optional.ofNullable(NamespacedKey.fromString(key))
              .map(server::getAdvancement)
              .ifPresent(advancement -> {
                AdvancementProgress progress = progress(player, advancement);
                awarded.keySet().forEach(progress::awardCriteria);
              }));
    } finally {
      restoring.remove(player.getUniqueId());
    }
  }

  /** Who hears about completed advancements, for season firsts. */
  void milestones(Milestones listener) {
    this.milestones = listener;
  }

  /**
   * A completed advancement with a display goes to the season firsts, unless unarchive is giving it
   * back.
   */
  @EventHandler(priority = EventPriority.MONITOR)
  void onCompleted(PlayerAdvancementDoneEvent event) {
    Milestones listening = milestones;
    Advancement advancement = event.getAdvancement();
    AdvancementDisplay display = advancement.getDisplay();
    if (listening == null
        || display == null
        || isRecipe(advancement)
        || restoring.contains(event.getPlayer().getUniqueId())) {
      return;
    }
    listening.advancementDone(
        new PlayerId(event.getPlayer().getUniqueId()),
        advancement.getKey().asString(),
        PlainTextComponentSerializer.plainText().serialize(display.title()));
  }

  /** Advancements given back by unarchive aren't announced in chat. */
  @EventHandler(priority = EventPriority.HIGHEST)
  void onDone(PlayerAdvancementDoneEvent event) {
    if (restoring.contains(event.getPlayer().getUniqueId())) {
      event.message(null);
    }
  }

  @Override
  public void forgetRecipes(PlayerId id) {
    Player player = online(id);
    player.undiscoverRecipes(player.getDiscoveredRecipes());
  }

  private static boolean isRecipe(Advancement advancement) {
    return advancement.getKey().getKey().startsWith(RECIPES);
  }

  private static AdvancementProgress progress(Player player, Advancement advancement) {
    return player.getAdvancementProgress(advancement);
  }

  private Player online(PlayerId id) {
    return players.player(id).orElseThrow(() -> new IllegalStateException(id + " is offline"));
  }

  private List<Advancement> all() {
    List<Advancement> all = new ArrayList<>();
    for (Iterator<Advancement> it = server.advancementIterator(); it.hasNext(); ) {
      all.add(it.next());
    }
    return all;
  }

  /**
   * Whether an advancement counts toward "completed / visible": as the advancement screen shows
   * them, so no recipe unlocks (they have no display) and no hidden entries.
   */
  private static boolean counted(Advancement advancement) {
    AdvancementDisplay display = advancement.getDisplay();
    return display != null && !display.isHidden();
  }
}
