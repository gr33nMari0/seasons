// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.archive.ArchiveRecord;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.player.FurthestBlock;
import com.petepeterepeat.seasons.core.player.PlayerRecord;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.Statistics;
import com.petepeterepeat.seasons.core.season.Awards;
import com.petepeterepeat.seasons.core.season.FirstsTracker;
import com.petepeterepeat.seasons.core.season.HumanTime;
import com.petepeterepeat.seasons.core.season.SeasonAwards;
import com.petepeterepeat.seasons.core.season.SeasonFirst;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The season card (see Stats, season cards and awards in the spec): a written book opened from the
 * plaque anchor, built from the player's archive. The first page has the highlights, then their
 * season firsts and the awards they won, then every General-tab statistic as this season's
 * difference. A card for an imported Season 1 cell says so and shows the cell's signs, since its
 * statistics are unknown.
 *
 * @param pages each page's lines
 */
public record SeasonCard(Message title, List<List<Line>> pages) {
  /** How many entries a book page holds; most entries wrap onto two lines. */
  static final int PER_PAGE = 6;

  private static final Pattern STAT_ID = Pattern.compile("minecraft:[a-z0-9_]+");

  /** Statistics counted in ticks. */
  private static final Set<String> TICKS =
      Set.of(
          "minecraft:play_time",
          "minecraft:total_world_time",
          "minecraft:time_since_death",
          "minecraft:time_since_rest",
          "minecraft:sneak_time");

  /** The distances the card's "travelled" adds up, which the furthest-travelled award counts. */
  public static final List<String> TRAVEL =
      List.of(
          "minecraft:walk_one_cm",
          "minecraft:walk_on_water_one_cm",
          "minecraft:walk_under_water_one_cm",
          "minecraft:sprint_one_cm",
          "minecraft:swim_one_cm",
          "minecraft:fly_one_cm",
          "minecraft:aviate_one_cm",
          "minecraft:boat_one_cm",
          "minecraft:minecart_one_cm",
          "minecraft:horse_one_cm");

  /**
   * One line of the card.
   *
   * @param plain the same line as plain text with statistic ids, for logs and the scenario hooks
   */
  public record Line(Message message, String plain) {}

  public SeasonCard {
    pages = pages.stream().map(List::copyOf).toList();
  }

  /** Every line of every page, as plain text. */
  public List<String> plainLines() {
    return pages.stream().flatMap(List::stream).map(Line::plain).toList();
  }

  /** The pages as messages, for a book. */
  public List<List<Message>> messages() {
    return pages.stream().map(page -> page.stream().map(Line::message).toList()).toList();
  }

  /**
   * The card for one archive.
   *
   * @param record the player's record, for the days they played; empty if there is none
   * @param firsts the season firsts this player earned that season
   * @param awards the season's awards, once its Intermission has worked them out
   */
  public static SeasonCard of(
      ArchiveRecord archive,
      Optional<PlayerRecord> record,
      List<SeasonFirst> firsts,
      Optional<SeasonAwards> awards,
      Messages messages) {
    String season = Integer.toString(archive.season());
    Message title = messages.get("card.title", "name", archive.name(), "season", season);
    List<List<Line>> pages = new ArrayList<>();
    List<Line> head = new ArrayList<>();
    head.add(line(messages, "card.name", "name", archive.name()));
    if (archive.imported().isPresent()) {
      head.add(line(messages, "card.imported", "season", season));
      for (String sign : archive.imported().get().signs()) {
        head.add(line(messages, "card.sign", "text", sign.replace('\n', ' ')));
      }
      paged(pages, head);
      return new SeasonCard(title, pages);
    }
    head.add(line(messages, "card.season", "season", season));
    Optional<PlayerRecord.SeasonPlay> play =
        record.flatMap(r -> Optional.ofNullable(r.seasons().get(archive.season())));
    play.ifPresent(p ->
        head.add(
            line(messages, "card.days", "first", day(p.firstSeen()), "last", day(p.lastSeen()))));
    Map<String, Long> stats = archive.statistics();
    head.add(
        line(
            messages,
            "card.play-time",
            "value",
            time(messages, stats.getOrDefault("minecraft:play_time", 0L))));
    head.add(line(messages, "card.deaths", "value", number(stats, "minecraft:deaths")));
    head.add(line(messages, "card.mob-kills", "value", number(stats, "minecraft:mob_kills")));
    head.add(line(messages, "card.player-kills", "value", number(stats, "minecraft:player_kills")));
    long travelled = TRAVEL.stream().mapToLong(id -> stats.getOrDefault(id, 0L)).sum();
    head.add(line(messages, "card.travelled", "value", distance(messages, travelled)));
    head.add(line(messages, "card.diamonds", "value", number(stats, Statistics.DIAMONDS_MINED)));
    head.add(line(messages, "card.debris", "value", number(stats, Statistics.DEBRIS_MINED)));
    head.add(line(messages, "card.placed", "value", number(stats, Statistics.BLOCKS_PLACED)));
    head.add(line(messages, "card.mined", "value", number(stats, Statistics.BLOCKS_MINED)));
    head.add(
        line(
            messages,
            "card.advancements",
            "completed",
            Integer.toString(archive.advancements().completed()),
            "visible",
            Integer.toString(archive.advancements().visible())));
    Optional<FurthestBlock.Best> furthest =
        archive.furthest().flatMap(f -> f.fromSpawn().or(f::fromOrigin));
    head.add(
        furthest
            .map(best ->
                line(
                    messages,
                    "card.furthest",
                    "distance",
                    group(best.distance()),
                    "x",
                    Integer.toString(best.at().blockX()),
                    "z",
                    Integer.toString(best.at().blockZ())))
            .orElseGet(() -> line(messages, "card.furthest-none")));
    if (firsts.stream().anyMatch(f -> f.key().equals(FirstsTracker.DRAGON))) {
      // The dragon credit (questions 206).
      head.add(line(messages, "card.dragon"));
    }
    Position end = archive.finalPosition();
    head.add(
        line(
            messages,
            "card.ended",
            "x",
            Integer.toString(end.blockX()),
            "y",
            Integer.toString(end.blockY()),
            "z",
            Integer.toString(end.blockZ()),
            "dimension",
            end.dimension()));
    paged(pages, head);

    List<Line> earned = new ArrayList<>();
    earned.add(line(messages, "card.firsts"));
    if (firsts.isEmpty()) {
      earned.add(line(messages, "card.firsts-none"));
    }
    for (SeasonFirst first : firsts) {
      Message what = FirstsTracker.what(messages, first);
      earned.add(new Line(what, Messages.plain(what)));
    }
    paged(pages, earned);

    if (awards.isPresent()) {
      List<Line> won = new ArrayList<>();
      won.add(line(messages, "card.awards"));
      List<SeasonAwards.Award> theirs = awards.get().wonBy(archive.player());
      if (theirs.isEmpty()) {
        won.add(line(messages, "card.awards-none"));
      }
      for (SeasonAwards.Award award : theirs) {
        String name = Awards.name(messages, award.id());
        won.add(
            Awards.valueText(messages, award)
                .map(value -> line(messages, "card.award", "award", name, "value", value))
                .orElseGet(() -> line(messages, "card.award-plain", "award", name)));
      }
      paged(pages, won);
    }

    List<Line> general = new ArrayList<>();
    general.add(line(messages, "card.general"));
    for (Map.Entry<String, Long> stat : new TreeMap<>(stats).entrySet()) {
      if (!STAT_ID.matcher(stat.getKey()).matches()) {
        continue;
      }
      general.add(
          statLine(messages, stat.getKey(), value(messages, stat.getKey(), stat.getValue())));
    }
    paged(pages, general);
    return new SeasonCard(title, pages);
  }

  /** A General-tab line: the statistic's own name, as the client's language has it. */
  private static Line statLine(Messages messages, String id, String value) {
    Message line = messages.get("card.stat", "value", value);
    String lang = "stat." + id.replace(':', '.');
    Message named =
        new Message(line.template().replace("<stat>", "<lang:" + lang + ">"), line.placeholders());
    String plain = Messages.plain(new Message(line.template(), withStat(line, id)));
    return new Line(named, plain);
  }

  private static Map<String, String> withStat(Message line, String id) {
    Map<String, String> values = new java.util.HashMap<>(line.placeholders());
    values.put("stat", id);
    return values;
  }

  /** Lines into pages of {@link #PER_PAGE}, starting a new page. */
  private static void paged(List<List<Line>> pages, List<Line> lines) {
    for (int i = 0; i < lines.size(); i += PER_PAGE) {
      pages.add(List.copyOf(lines.subList(i, Math.min(lines.size(), i + PER_PAGE))));
    }
  }

  private static Line line(Messages messages, String key, String... placeholders) {
    Message message = messages.get(key, placeholders);
    return new Line(message, Messages.plain(message));
  }

  /** A statistic's value as the card shows it: ticks as time, centimetres as blocks. */
  public static String value(Messages messages, String id, long value) {
    if (TICKS.contains(id)) {
      return time(messages, value);
    }
    if (id.endsWith("_one_cm")) {
      return distance(messages, value);
    }
    if (id.startsWith("minecraft:damage_")) {
      // Damage is counted in tenths of a point.
      return String.format(Locale.ROOT, "%,.1f", value / 10.0);
    }
    return group(value);
  }

  private static String number(Map<String, Long> stats, String id) {
    return group(stats.getOrDefault(id, 0L));
  }

  /** A number with thousands separators. */
  public static String group(long value) {
    return String.format(Locale.ROOT, "%,d", value);
  }

  /** Ticks as hours and minutes. */
  public static String time(Messages messages, long ticks) {
    Duration duration = Duration.ofSeconds(ticks / 20);
    long hours = duration.toHours();
    long minutes = duration.toMinutesPart();
    if (hours == 0) {
      return HumanTime.duration(messages, minutes == 0 ? duration : Duration.ofMinutes(minutes));
    }
    return messages.text(
        "card.hours-minutes", "hours", Long.toString(hours), "minutes", Long.toString(minutes));
  }

  /** Centimetres as blocks. */
  public static String distance(Messages messages, long centimetres) {
    return messages.text("card.blocks", "n", group(centimetres / 100));
  }

  private static String day(Instant at) {
    return LocalDate.ofInstant(at, ZoneId.systemDefault()).toString();
  }
}
