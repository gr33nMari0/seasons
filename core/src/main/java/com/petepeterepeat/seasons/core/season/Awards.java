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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Awards (see Stats, season cards and awards in the spec): worked out once, when a season's
 * Intermission begins, from the archives made by then (questions 199), in the order config.yml
 * lists them. Ties share an award, and an award whose best is 0 goes to nobody. They are saved in
 * the season state first; then they are drawn (trophies and a plaque line in each winner's cell,
 * lines on the museum's plaque for the season), copied into the winners' archives, and, unless
 * nothing was earned at all, announced with a recap of the season's firsts, in chat and in one
 * Discord post.
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
  private final Set<Integer> working = new HashSet<>();
  private Consumer<SeasonAwards> draw = awards -> {};

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

  /** Draws a season's awards once they are saved: the winners' cells and the museum. */
  public void bind(Consumer<SeasonAwards> drawing) {
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
        || current.get().awards(season).isPresent()
        || !working.add(season)) {
      return;
    }
    var _ =
        writer
            .submit(() -> archives.all(season))
            .whenCompleteAsync(
                (found, failure) -> {
                  try {
                    if (failure != null) {
                      LOG.error("Can't read Season {}'s archives for its awards", season, failure);
                      return;
                    }
                    work(season, found);
                  } finally {
                    working.remove(season);
                  }
                },
                serverThread);
  }

  private void work(int season, List<ArchiveRecord> found) {
    Optional<SeasonState> current = seasons.state();
    if (current.isEmpty() || current.get().awards(season).isPresent()) {
      return;
    }
    Optional<SeasonFirst> slayer = current.get().first(FirstsTracker.DRAGON);
    SeasonAwards worked =
        new SeasonAwards(
            season, clock.instant(), compute(config.get().config().awards(), found, slayer));
    List<SeasonFirst> firsts = current.get().firsts(season);
    seasons.updateAwards(worked);
    // Announced only once they are on disk, so a restart neither loses nor repeats them.
    seasons.afterSave(() -> {
      draw.accept(worked);
      var _ = honour(worked);
      if (worked.given().isEmpty() && firsts.isEmpty()) {
        // Nothing to tell: no award was earned and nobody was first to anything.
        return;
      }
      Messages messages = config.get().messages();
      List<Message> lines = new ArrayList<>(lines(messages, worked));
      lines.addAll(recap(messages, season, firsts));
      announcer.broadcast(lines);
      announcer.discord(lines);
    });
  }

  /** Copies each winner's awards into their archive. */
  private java.util.concurrent.CompletableFuture<Integer> honour(SeasonAwards worked) {
    Map<PlayerId, List<String>> won = new LinkedHashMap<>();
    for (SeasonAwards.Award award : worked.awards()) {
      for (SeasonAwards.Winner winner : award.winners()) {
        won.computeIfAbsent(winner.player(), p -> new ArrayList<>()).add(award.id());
      }
    }
    return writer.submit(() -> {
      for (Map.Entry<PlayerId, List<String>> winner : won.entrySet()) {
        try {
          archives.update(
              winner.getKey(),
              worked.season(),
              record ->
                  record.withHonours(
                      new ArchiveRecord.Honours(record.honours().firsts(), winner.getValue())));
        } catch (IOException e) {
          LOG.error("Can't copy awards into {}'s archive", winner.getKey(), e);
        }
      }
      return won.size();
    });
  }

  /**
   * Works out each award from a season's archives: the best value of its measure, and everyone with
   * it. Imported archives, whose statistics are unknown, take no part.
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
      long best = 0;
      List<SeasonAwards.Winner> winners = new ArrayList<>();
      for (ArchiveRecord archive : played) {
        long value = value(measure, archive, slayer);
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
  static long value(AwardMeasure measure, ArchiveRecord archive, Optional<SeasonFirst> slayer) {
    Map<String, Long> stats = archive.statistics();
    return switch (measure) {
      case AwardMeasure.Named named ->
          switch (named) {
            case DEATHS -> stats.getOrDefault("minecraft:deaths", 0L);
            case TRAVELLED ->
                SeasonCard.TRAVEL.stream().mapToLong(id -> stats.getOrDefault(id, 0L)).sum();
            case PLAY_TIME -> stats.getOrDefault("minecraft:play_time", 0L);
            case DIAMONDS -> stats.getOrDefault(Statistics.DIAMONDS_MINED, 0L);
            case DRAGON ->
                slayer.filter(s -> s.player().equals(archive.player())).isPresent() ? 1 : 0;
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
