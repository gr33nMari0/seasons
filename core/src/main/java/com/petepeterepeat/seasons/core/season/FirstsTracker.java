// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.season;

import com.petepeterepeat.seasons.core.announce.Announcer;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Inventories;
import com.petepeterepeat.seasons.core.port.Milestones;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Players;
import com.petepeterepeat.seasons.core.port.ScheduledTask;
import com.petepeterepeat.seasons.core.port.Scheduler;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Season firsts (see Stats, season cards and awards in the spec): the first player this season to
 * complete each advancement, to reach the Nether and the End, and to carry a diamond, an elytra or
 * an item on {@code firsts.rareItems}. Each is recorded once per season, broadcast in chat and
 * posted to Discord. Only while the season is being played; the dragon slayer comes with M4.
 */
public final class FirstsTracker implements Milestones {
  /** How often online players' inventories are checked for first items. */
  public static final Duration SWEEP = Duration.ofSeconds(10);

  /** Items that are always a first, besides {@code firsts.rareItems}. */
  private static final Set<String> ALWAYS = Set.of("minecraft:diamond", "minecraft:elytra");

  public static final String NETHER = "nether";
  public static final String END = "end";
  public static final String ADVANCEMENT = "advancement:";
  public static final String ITEM = "item:";

  private final Players players;
  private final Inventories inventories;
  private final Scheduler scheduler;
  private final Supplier<Optional<SeasonState>> state;
  private final Consumer<SeasonFirst> record;
  private final Predicate<PlayerId> held;
  private final ConfigHolder config;
  private final Announcer announcer;
  private final Clock clock;
  private @Nullable ScheduledTask sweep;

  /**
   * @param record saves a new first in the season state
   * @param held players being archived, unarchived or caught up at login, who reach nothing
   */
  public FirstsTracker(
      Players players,
      Inventories inventories,
      Scheduler scheduler,
      Supplier<Optional<SeasonState>> state,
      Consumer<SeasonFirst> record,
      Predicate<PlayerId> held,
      ConfigHolder config,
      Announcer announcer,
      Clock clock) {
    this.players = players;
    this.inventories = inventories;
    this.scheduler = scheduler;
    this.state = state;
    this.record = record;
    this.held = held;
    this.config = config;
    this.announcer = announcer;
    this.clock = clock;
  }

  public void start() {
    sweep = scheduler.runGlobalRepeating(SWEEP, this::sweep);
  }

  public void stop() {
    if (sweep != null) {
      sweep.cancel();
      sweep = null;
    }
  }

  @Override
  public void advancementDone(PlayerId player, String advancement, String title) {
    reach(player, ADVANCEMENT + advancement, title);
  }

  @Override
  public void enteredDimension(PlayerId player, String dimension) {
    switch (dimension) {
      case "minecraft:the_nether" -> reach(player, NETHER, "");
      case "minecraft:the_end" -> reach(player, END, "");
      default -> {}
    }
  }

  /** Checks every online player's inventory once for first items. */
  public void sweep() {
    if (!playing()) {
      return;
    }
    Set<String> tracked = tracked();
    for (PlayerRef online : players.online()) {
      if (held.test(online.id())) {
        continue;
      }
      for (String id : inventories.carriedItemIds(online.id())) {
        if (tracked.contains(id)) {
          reach(online.id(), ITEM + id, itemName(id));
        }
      }
    }
  }

  /** The item ids that count, with their namespace. */
  private Set<String> tracked() {
    Set<String> tracked = new LinkedHashSet<>(ALWAYS);
    for (String id : config.get().config().firsts().rareItems()) {
      tracked.add(id.contains(":") ? id : "minecraft:" + id);
    }
    return tracked;
  }

  private boolean playing() {
    return state.get().map(s -> s.phase() instanceof Phase.Active).orElse(false);
  }

  private void reach(PlayerId player, String key, String label) {
    Optional<SeasonState> current = state.get();
    Optional<PlayerRef> online = players.find(player);
    if (current.isEmpty()
        || !(current.get().phase() instanceof Phase.Active)
        || online.isEmpty()
        || held.test(player)
        || current.get().first(key).isPresent()) {
      return;
    }
    SeasonFirst first =
        new SeasonFirst(
            current.get().season(), key, label, player, online.get().name(), clock.instant());
    record.accept(first);
    Messages messages = config.get().messages();
    String what = messages.text(whatKey(key), "subject", label);
    announcer.broadcast("firsts.announce", "name", first.name(), "what", what);
    announcer.discord("firsts.discord", "name", first.name(), "what", what);
  }

  /** How a first reads, such as "reached the Nether", for chat and the season card. */
  public static Message what(Messages messages, SeasonFirst first) {
    return messages.get(whatKey(first.key()), "subject", first.label());
  }

  private static String whatKey(String key) {
    if (key.equals(NETHER)) {
      return "firsts.what.nether";
    }
    if (key.equals(END)) {
      return "firsts.what.end";
    }
    if (key.startsWith(ITEM)) {
      return "firsts.what.item";
    }
    return "firsts.what.advancement";
  }

  /** Such as "Heavy Core" for {@code minecraft:heavy_core}. */
  static String itemName(String id) {
    String bare = id.substring(id.indexOf(':') + 1);
    StringBuilder name = new StringBuilder();
    for (String word : bare.split("_", -1)) {
      if (word.isEmpty()) {
        continue;
      }
      if (!name.isEmpty()) {
        name.append(' ');
      }
      name.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
    }
    return name.toString();
  }
}
