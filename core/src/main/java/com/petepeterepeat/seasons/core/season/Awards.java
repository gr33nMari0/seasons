// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.season;

import com.petepeterepeat.seasons.core.announce.Announcer;
import com.petepeterepeat.seasons.core.archive.ArchiveFiles;
import com.petepeterepeat.seasons.core.archive.ArchiveRecord;
import com.petepeterepeat.seasons.core.config.AwardMeasure;
import com.petepeterepeat.seasons.core.config.ConfigSnapshot;
import com.petepeterepeat.seasons.core.config.SeasonsConfig;
import com.petepeterepeat.seasons.core.hall.SeasonCard;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.player.FurthestBlock;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Statistics;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Awards (see Stats, season cards and awards in the spec): worked out when a season's Intermission
 * begins, from the archives made by then, in the order config.yml lists them, and worked out again,
 * with the same list, whenever a player is archived into the season later, so a player offline at
 * season end takes part once they are (questions 212). Ties share an award, and an award whose best
 * is 0 goes to nobody; the dragon slayer is the season's slayer, archived or not. Each result is
 * saved in the season state with what is left to do; then it is drawn (trophies and a plaque line
 * in each winner's cell, lines on the museum's plaque for the season), copied into the archives it
 * changes, and announced: in full with a recap of the season's firsts the first time, unless
 * nothing was earned at all, and as a change after that, in chat and in one Discord post. A restart
 * finishes whatever is left (questions 213).
 */
public final class Awards {
  private static final Logger LOG = LoggerFactory.getLogger(Awards.class);

  /** Firsts the recap names one by one; the rest it counts per player. */
  private static final List<String> NAMED =
      List.of(
          FirstsTracker.NETHER,
          FirstsTracker.END,
          FirstsTracker.ITEM + "minecraft:diamond",
          FirstsTracker.ITEM + "minecraft:elytra",
          FirstsTracker.DRAGON);

  private final Supplier<ConfigSnapshot> config;
  private final ArchiveFiles archives;
  private final SerialWriter writer;
  private final SeasonService seasons;
  private final Announcer announcer;
  private final Clock clock;
  private final Executor serverThread;

  /** Seasons whose archives are being read; one at a time per season. */
  private final Set<Integer> working = new HashSet<>();

  /** Seasons archived into while they were being worked out, to work out again after. */
  private final Set<Integer> again = new HashSet<>();

  /** Seasons whose awards are being settled; one at a time per season. */
  private final Set<Integer> settling = new HashSet<>();

  private BiConsumer<Integer, Set<PlayerId>> draw = (season, players) -> {};

  public Awards(
      Supplier<ConfigSnapshot> config,
      ArchiveFiles archives,
      SerialWriter writer,
      SeasonService seasons,
      Announcer announcer,
      Clock clock,
      Executor serverThread) {
    this.config = config;
    this.archives = archives;
    this.writer = writer;
    this.seasons = seasons;
    this.announcer = announcer;
    this.clock = clock;
    this.serverThread = serverThread;
  }

  /** Draws a season's awards in these players' cells, and on the museum's plaque. */
  public void bind(BiConsumer<Integer, Set<PlayerId>> drawing) {
    this.draw = drawing;
  }

  /**
   * The Intermission has begun, or a restart found the server in it: works out the season's awards
   * unless they already are. On the server thread.
   */
  public void intermission(int season) {
    Optional<SeasonState> current = seasons.state();
    if (current.isEmpty()
        || current.get().season() != season
        || !(current.get().phase() instanceof Phase.Intermission)
        || current.get().awards(season).isPresent()) {
      return;
    }
    refresh(season);
  }

  /**
   * A player's archive into a season is done: if the season's awards are worked out without them,
   * works them out again. On the server thread.
   */
  public void archived(PlayerId player, int season) {
    if (working.contains(season)) {
      again.add(season);
      return;
    }
    seasons
        .state()
        .flatMap(s -> s.awards(season))
        .filter(worked -> !worked.counted().contains(player))
        .ifPresent(worked -> refresh(season));
  }

  /**
   * At startup, on the server thread: works awards out again where an archive was made that they
   * didn't hear of, as when a crash came just after it, and finishes what a crash left undone.
   */
  public void start() {
    for (SeasonAwards worked : seasons.state().map(SeasonState::awards).orElse(List.of())) {
      int season = worked.season();
      var _ =
          writer
              .submit(() -> archives.players(season))
              .whenCompleteAsync(
                  (players, failure) -> {
                    Optional<SeasonAwards> now = seasons.state().flatMap(s -> s.awards(season));
                    if (now.isEmpty()) {
                      return;
                    }
                    if (failure == null && !now.get().counted().containsAll(players)) {
                      refresh(season);
                    } else if (now.get().pending().isPresent()) {
                      settle(now.get());
                    }
                  },
                  serverThread);
    }
  }

  /** Reads the season's archives and works its awards out from them. */
  private void refresh(int season) {
    if (!working.add(season)) {
      again.add(season);
      return;
    }
    var _ =
        writer
            .submit(() -> archives.all(season))
            .whenCompleteAsync(
                (found, failure) -> {
                  try {
                    String number = Integer.toString(season);
                    if (failure != null) {
                      LOG.error("Can't read Season {}'s archives for its awards", season, failure);
                      announcer.admins(
                          SeasonService.ADMIN_PERMISSION,
                          "awards.unreadable",
                          "season",
                          number,
                          "error",
                          String.valueOf(failure.getMessage()));
                      return;
                    }
                    if (!found.damaged().isEmpty()) {
                      announcer.admins(
                          SeasonService.ADMIN_PERMISSION,
                          "awards.damaged",
                          "season",
                          number,
                          "files",
                          String.join(", ", found.damaged()));
                    }
                    work(season, found.records());
                  } finally {
                    working.remove(season);
                    if (again.remove(season)) {
                      refresh(season);
                    }
                  }
                },
                serverThread);
  }

  private void work(int season, List<ArchiveRecord> found) {
    Optional<SeasonState> current = seasons.state();
    if (current.isEmpty()) {
      return;
    }
    Optional<SeasonAwards> before = current.get().awards(season);
    // Worked out again with the list they were first worked out with, not today's config.
    Map<String, SeasonsConfig.Award> list =
        before.map(Awards::listOf).orElseGet(() -> config.get().config().awards());
    // The season's own slayer, even if the next season has launched meanwhile.
    Optional<SeasonFirst> slayer =
        current.get().firsts(season).stream()
            .filter(f -> f.key().equals(FirstsTracker.DRAGON))
            .findFirst();
    List<SeasonAwards.Award> computed = compute(list, found, slayer);
    Set<PlayerId> counted = new HashSet<>();
    found.forEach(archive -> counted.add(archive.player()));
    if (before.isPresent() && before.get().awards().equals(computed)) {
      // Nothing changed: remember who was counted, and finish anything left undone.
      SeasonAwards same = before.get().withCounted(counted);
      if (!same.equals(before.get())) {
        seasons.updateAwards(same);
      }
      if (same.pending().isPresent()) {
        seasons.afterSave(() -> settle(same));
      }
      return;
    }
    Set<PlayerId> players = new LinkedHashSet<>();
    before.ifPresent(b -> {
      players.addAll(winners(b.awards()));
      b.pending().ifPresent(p -> players.addAll(p.players()));
    });
    players.addAll(winners(computed));
    boolean first = before.isEmpty() || before.get().pending().map(p -> p.first()).orElse(false);
    SeasonAwards worked =
        new SeasonAwards(
            season,
            clock.instant(),
            computed,
            counted,
            Optional.of(new SeasonAwards.Pending(first, players, false)));
    seasons.updateAwards(worked);
    // Drawn, copied and announced only once they are on disk; a restart finishes what is left.
    seasons.afterSave(() -> settle(worked));
  }

  /**
   * Does what is left after awards were worked out: draws them, announces them, then copies them
   * into the archives they change. Done is saved only when all of it is; until then a restart does
   * it again, so nothing is lost, though an announcement may be made twice after a crash.
   */
  private void settle(SeasonAwards worked) {
    SeasonAwards.Pending pending = worked.pending().orElseThrow();
    int season = worked.season();
    if (!settling.add(season)) {
      return;
    }
    draw.accept(season, pending.players());
    if (!pending.announced()) {
      announce(worked, pending.first());
    }
    var _ =
        honour(worked, pending.players())
            .whenCompleteAsync(
                (copied, failure) -> {
                  settling.remove(season);
                  Optional<SeasonAwards> now = seasons.state().flatMap(s -> s.awards(season));
                  if (now.isEmpty()
                      || !now.get().awards().equals(worked.awards())
                      || !now.get().pending().equals(worked.pending())) {
                    // Worked out again meanwhile: settle that instead.
                    now.filter(n -> n.pending().isPresent())
                        .ifPresent(n -> seasons.afterSave(() -> settle(n)));
                    return;
                  }
                  if (failure != null || !Boolean.TRUE.equals(copied)) {
                    LOG.error(
                        "Season {}'s awards weren't copied into every archive", season, failure);
                    announcer.admins(
                        SeasonService.ADMIN_PERMISSION,
                        "awards.copy-failed",
                        "season",
                        Integer.toString(season));
                    seasons.updateAwards(
                        now.get()
                            .withPending(
                                Optional.of(
                                    new SeasonAwards.Pending(
                                        pending.first(), pending.players(), true))));
                    return;
                  }
                  seasons.updateAwards(now.get().withPending(Optional.empty()));
                },
                serverThread);
  }

  private void announce(SeasonAwards worked, boolean first) {
    int season = worked.season();
    Messages messages = config.get().messages();
    List<Message> lines = new ArrayList<>();
    if (first) {
      List<SeasonFirst> firsts = seasons.state().map(s -> s.firsts(season)).orElse(List.of());
      if (worked.given().isEmpty() && firsts.isEmpty()) {
        // Nothing to tell: no award was earned and nobody was first to anything.
        return;
      }
      lines.addAll(lines(messages, worked));
      lines.addAll(recap(messages, season, firsts));
    } else {
      lines.add(messages.get("awards.changed", "season", Integer.toString(season)));
      worked.given().forEach(award -> lines.add(line(messages, award)));
    }
    announcer.broadcast(lines);
    announcer.discord(lines);
  }

  /** The award list a season's awards were worked out with. */
  private static Map<String, SeasonsConfig.Award> listOf(SeasonAwards worked) {
    Map<String, SeasonsConfig.Award> list = new LinkedHashMap<>();
    worked.awards().forEach(a -> list.put(a.id(), new SeasonsConfig.Award(a.measure(), null)));
    return list;
  }

  private static Set<PlayerId> winners(List<SeasonAwards.Award> awards) {
    Set<PlayerId> players = new LinkedHashSet<>();
    awards.forEach(a -> a.winners().forEach(w -> players.add(w.player())));
    return players;
  }

  /**
   * Writes each player's awards into their archive, if they have one yet; one who doesn't gets them
   * when they are archived. True if every archive there is was written.
   */
  private CompletableFuture<Boolean> honour(SeasonAwards worked, Set<PlayerId> players) {
    return writer.submit(() -> {
      boolean all = true;
      for (PlayerId player : players) {
        List<String> won = worked.wonBy(player).stream().map(SeasonAwards.Award::id).toList();
        try {
          archives.updateHonours(
              player, worked.season(), honours -> new ArchiveRecord.Honours(honours.firsts(), won));
        } catch (IOException e) {
          LOG.error("Can't copy awards into {}'s archive", player, e);
          all = false;
        }
      }
      return all;
    });
  }

  /**
   * Works out each award from a season's archives: the best value of its measure, and everyone with
   * it. Imported archives, whose statistics are unknown, take no part. The dragon slayer award goes
   * to the season's slayer.
   */
  public static List<SeasonAwards.Award> compute(
      Map<String, SeasonsConfig.Award> list,
      List<ArchiveRecord> found,
      Optional<SeasonFirst> slayer) {
    List<ArchiveRecord> played = found.stream().filter(a -> a.imported().isEmpty()).toList();
    List<SeasonAwards.Award> awards = new ArrayList<>();
    list.forEach((id, setting) -> {
      if (!setting.on()) {
        return;
      }
      AwardMeasure measure = setting.parsed().orElseThrow();
      List<SeasonAwards.Winner> winners = new ArrayList<>();
      if (measure == AwardMeasure.Named.DRAGON) {
        // The season's slayer, whether or not they are archived yet.
        slayer.ifPresent(s -> winners.add(new SeasonAwards.Winner(s.player(), s.name())));
        awards.add(new SeasonAwards.Award(id, measure.text(), winners.size(), winners));
        return;
      }
      long best = 0;
      for (ArchiveRecord archive : played) {
        long value = value(measure, archive);
        if (value <= 0 || value < best) {
          continue;
        }
        if (value > best) {
          best = value;
          winners.clear();
        }
        winners.add(new SeasonAwards.Winner(archive.player(), archive.name()));
      }
      winners.sort(Comparator.comparing(w -> w.name().toLowerCase(Locale.ROOT)));
      awards.add(new SeasonAwards.Award(id, measure.text(), best, winners));
    });
    return awards;
  }

  /** One archive's value of a measure. */
  static long value(AwardMeasure measure, ArchiveRecord archive) {
    Map<String, Long> stats = archive.statistics();
    return switch (measure) {
      case AwardMeasure.Named named ->
          switch (named) {
            case DEATHS -> stats.getOrDefault("minecraft:deaths", 0L);
            case TRAVELLED ->
                SeasonCard.TRAVEL.stream().mapToLong(id -> stats.getOrDefault(id, 0L)).sum();
            case PLAY_TIME -> stats.getOrDefault("minecraft:play_time", 0L);
            case DIAMONDS -> stats.getOrDefault(Statistics.DIAMONDS_MINED, 0L);
            // Not from archives: see compute.
            case DRAGON -> 0;
            case FURTHEST_BUILDER -> furthest(archive);
            case ADVANCEMENTS -> archive.advancements().completed();
            case OFF -> 0;
          };
      case AwardMeasure.Statistic statistic -> stats.getOrDefault(statistic.id(), 0L);
    };
  }

  /**
   * The furthest block placed from the season spawn, or from 0, 0 for a season that had none, as
   * the season card has it (questions 207).
   */
  private static long furthest(ArchiveRecord archive) {
    return archive
        .furthest()
        .flatMap(f -> f.fromSpawn().or(f::fromOrigin))
        .map(FurthestBlock.Best::distance)
        .orElse(0L);
  }

  /** A season's awards as chat lines: a heading, then each award someone won. */
  public static List<Message> lines(Messages messages, SeasonAwards awards) {
    String season = Integer.toString(awards.season());
    List<Message> lines = new ArrayList<>();
    if (awards.given().isEmpty()) {
      lines.add(messages.get("awards.none", "season", season));
      return lines;
    }
    lines.add(messages.get("awards.heading", "season", season));
    for (SeasonAwards.Award award : awards.given()) {
      lines.add(line(messages, award));
    }
    return lines;
  }

  /** One award as a line, such as "Most deaths: Alex and Sam (5)". */
  public static Message line(Messages messages, SeasonAwards.Award award) {
    String winners = names(messages, award.winners().stream().map(w -> w.name()).toList());
    Optional<String> value = valueText(messages, award);
    return value
        .map(v ->
            messages.get(
                "awards.line", "award", name(messages, award.id()), "winners", winners, "value", v))
        .orElseGet(() ->
            messages.get(
                "awards.line-plain", "award", name(messages, award.id()), "winners", winners));
  }

  /** An award's name: from messages.yml, or for an award of the server's own, from its id. */
  public static String name(Messages messages, String id) {
    String key = "awards.names." + id;
    if (messages.has(key)) {
      return messages.text(key);
    }
    String words = id.replace('-', ' ');
    return words.isEmpty()
        ? id
        : words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
  }

  /** How an award's winning value reads, such as "1,500 blocks"; none for the dragon slayer. */
  public static Optional<String> valueText(Messages messages, SeasonAwards.Award award) {
    Optional<AwardMeasure> measure = AwardMeasure.parse(award.measure());
    if (measure.isEmpty()) {
      return Optional.of(SeasonCard.group(award.value()));
    }
    return switch (measure.get()) {
      case AwardMeasure.Named named ->
          switch (named) {
            case TRAVELLED -> Optional.of(SeasonCard.distance(messages, award.value()));
            case PLAY_TIME -> Optional.of(SeasonCard.time(messages, award.value()));
            case FURTHEST_BUILDER ->
                Optional.of(messages.text("card.blocks", "n", SeasonCard.group(award.value())));
            case DRAGON, OFF -> Optional.empty();
            case DEATHS, DIAMONDS, ADVANCEMENTS -> Optional.of(SeasonCard.group(award.value()));
          };
      case AwardMeasure.Statistic statistic ->
          Optional.of(SeasonCard.value(messages, statistic.id(), award.value()));
    };
  }

  /** Names as a list: "Alex", "Alex and Sam", "Alex, Pat and Sam". */
  public static String names(Messages messages, List<String> names) {
    if (names.size() <= 1) {
      return String.join("", names);
    }
    String and = messages.text("awards.and");
    return String.join(messages.text("awards.comma"), names.subList(0, names.size() - 1))
        + and
        + names.getLast();
  }

  /**
   * The season's firsts as the Intermission recaps them: the Nether, the End, the first diamond and
   * elytra and the dragon slayer by name, then how many other firsts each player earned.
   */
  public static List<Message> recap(Messages messages, int season, List<SeasonFirst> firsts) {
    String number = Integer.toString(season);
    List<Message> lines = new ArrayList<>();
    if (firsts.isEmpty()) {
      lines.add(messages.get("firsts.recap-none", "season", number));
      return lines;
    }
    lines.add(messages.get("firsts.header", "season", number));
    for (String key : NAMED) {
      firsts.stream()
          .filter(f -> f.key().equals(key))
          .findFirst()
          .ifPresent(first ->
              lines.add(
                  messages.get(
                      "firsts.recap-line",
                      "name",
                      first.name(),
                      "what",
                      Messages.plain(FirstsTracker.what(messages, first)))));
    }
    Map<PlayerId, int[]> counts = new LinkedHashMap<>();
    Map<PlayerId, String> names = new LinkedHashMap<>();
    // Each player once, in the order their first first came.
    for (SeasonFirst first : firsts) {
      if (NAMED.contains(first.key())) {
        continue;
      }
      int[] count = counts.computeIfAbsent(first.player(), p -> new int[2]);
      count[first.key().startsWith(FirstsTracker.ADVANCEMENT) ? 0 : 1]++;
      names.put(first.player(), first.name());
    }
    counts.forEach((player, count) -> {
      List<String> parts = new ArrayList<>();
      if (count[0] > 0) {
        parts.add(
            messages.text(
                count[0] == 1 ? "firsts.count.advancement" : "firsts.count.advancements",
                "n",
                Integer.toString(count[0])));
      }
      if (count[1] > 0) {
        parts.add(
            messages.text(
                count[1] == 1 ? "firsts.count.item" : "firsts.count.items",
                "n",
                Integer.toString(count[1])));
      }
      lines.add(
          messages.get(
              "firsts.recap-count",
              "name",
              names.getOrDefault(player, player.toString()),
              "what",
              String.join(messages.text("awards.and"), parts)));
    });
    return lines;
  }
}
