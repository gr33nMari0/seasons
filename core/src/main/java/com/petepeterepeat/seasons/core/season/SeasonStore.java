// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.season;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.petepeterepeat.seasons.core.config.Durations;
import com.petepeterepeat.seasons.core.config.ZonedTimes;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.season.Phase.Active;
import com.petepeterepeat.seasons.core.season.Phase.Archiving;
import com.petepeterepeat.seasons.core.season.Phase.Countdown;
import com.petepeterepeat.seasons.core.season.Phase.Intermission;
import com.petepeterepeat.seasons.core.store.AtomicFiles;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.gson.GsonConfigurationLoader;
import org.spongepowered.configurate.serialize.SerializationException;

/** Reads and writes {@code data/season.json}. Writes go through the {@link SerialWriter}. */
public final class SeasonStore {
  /** The {@code format-version} this build reads and writes. */
  public static final int FORMAT_VERSION = 1;

  private final Path file;
  private final SerialWriter writer;

  public SeasonStore(Path file, SerialWriter writer) {
    this.file = file;
    this.writer = writer;
  }

  /** The stored state, or empty on a server that has never recorded a season. */
  public Optional<SeasonState> load() throws IOException {
    if (Files.notExists(file)) {
      return Optional.empty();
    }
    return Optional.of(decode(Files.readString(file, UTF_8)));
  }

  /** Saves a state; the future completes once it is safely on disk. */
  public CompletableFuture<Void> save(SeasonState state) {
    String json = encode(state);
    return writer
        .submit(() -> {
          AtomicFiles.write(file, json.getBytes(UTF_8));
          return Boolean.TRUE;
        })
        .thenApply(done -> null);
  }

  static String encode(SeasonState state) {
    try {
      GsonConfigurationLoader loader = loader(null);
      ConfigurationNode node = loader.createNode();
      node.node("format-version").set(FORMAT_VERSION);
      node.node("season").set(state.season());
      switch (state.phase()) {
        case Active active -> {
          node.node("phase").set("active");
          node.node("stage").set(name(active.stage()));
        }
        case Countdown countdown -> {
          node.node("phase").set("ending-countdown");
          node.node("stage").set(name(countdown.stageBefore()));
          node.node("countdown", "startedAt").set(countdown.startedAt().toString());
          node.node("countdown", "endsAt").set(countdown.endsAt().toString());
        }
        case Archiving archiving -> node.node("phase").set("ending-archiving");
        case Intermission intermission -> node.node("phase").set("intermission");
      }
      if (state.plannedEnd().isPresent()) {
        node.node("plannedEnd").set(ZonedTimes.format(state.plannedEnd().get()));
      }
      if (state.plannedEndSetAt().isPresent()) {
        node.node("plannedEndSetAt").set(state.plannedEndSetAt().get().toString());
      }
      node.node("remindersSent")
          .setList(
              String.class,
              state.remindersSent().stream().sorted().map(Durations::format).toList());
      node.node("autoEndStarted").set(state.autoEndStarted());
      setInstant(node, "launchedAt", state.launchedAt());
      setInstant(node, "homecomingAt", state.homecomingAt());
      setInstant(node, "countdownInterrupted", state.countdownInterrupted());
      if (state.initSeason().isPresent()) {
        node.node("initSeason").set(state.initSeason().get());
      }
      node.node("launchApplied").set(state.launchApplied());
      Decorating decorating = state.decorating();
      setInstant(node.node("decorating"), "openedAt", decorating.openedAt());
      node.node("decorating", "open").set(decorating.open());
      for (Map.Entry<PlayerId, Instant> lock : decorating.lockAt().entrySet()) {
        node.node("decorating", "lockAt", lock.getKey().toString()).set(lock.getValue().toString());
      }
      for (SeasonFirst first : state.firsts()) {
        ConfigurationNode entry = node.node("firsts").appendListNode();
        entry.node("season").set(first.season());
        entry.node("key").set(first.key());
        entry.node("label").set(first.label());
        entry.node("player").set(first.player().toString());
        entry.node("name").set(first.name());
        entry.node("at").set(first.at().toString());
      }
      ZoneState zones = state.zones();
      for (Map.Entry<String, Boolean> zone : zones.unlocked().entrySet()) {
        node.node("zones", "unlocked", zone.getKey()).set(zone.getValue());
      }
      node.node("zones", "dragonKilled").set(zones.dragonKilled());
      node.node("zones", "reachedEnd")
          .setList(
              String.class, zones.reachedEnd().stream().map(PlayerId::toString).sorted().toList());
      dragon(node.node("dragon"), state.dragon());
      for (SeasonAwards season : state.awards()) {
        ConfigurationNode entry = node.node("awards").appendListNode();
        entry.node("season").set(season.season());
        entry.node("at").set(season.at().toString());
        for (SeasonAwards.Award award : season.awards()) {
          ConfigurationNode a = entry.node("awards").appendListNode();
          a.node("id").set(award.id());
          a.node("measure").set(award.measure());
          a.node("value").set(award.value());
          for (SeasonAwards.Winner winner : award.winners()) {
            ConfigurationNode w = a.node("winners").appendListNode();
            w.node("player").set(winner.player().toString());
            w.node("name").set(winner.name());
          }
        }
        entry
            .node("counted")
            .setList(
                String.class, season.counted().stream().map(PlayerId::toString).sorted().toList());
        if (season.pending().isPresent()) {
          SeasonAwards.Pending pending = season.pending().get();
          ConfigurationNode p = entry.node("pending");
          p.node("first").set(pending.first());
          p.node("announced").set(pending.announced());
          p.node("players")
              .setList(
                  String.class,
                  pending.players().stream().map(PlayerId::toString).sorted().toList());
        }
      }
      StringWriter out = new StringWriter();
      loader(out).save(node);
      return out.toString();
    } catch (IOException e) {
      throw new IllegalStateException("Can't encode the season state", e);
    }
  }

  static SeasonState decode(String json) throws IOException {
    ConfigurationNode node =
        GsonConfigurationLoader.builder()
            .source(() -> new BufferedReader(new StringReader(json)))
            .build()
            .load();
    int version = node.node("format-version").getInt(0);
    if (version < 1 || version > FORMAT_VERSION) {
      throw new IOException(
          "season.json has format-version " + version + "; this Seasons reads " + FORMAT_VERSION);
    }
    int season = node.node("season").getInt(0);
    if (season < 1) {
      throw new IOException("season.json has no season number");
    }
    Stage stage = stage(node.node("stage").getString("open"));
    String phaseName = node.node("phase").getString("");
    Phase phase =
        switch (phaseName) {
          case "active" -> new Active(stage);
          case "ending-countdown" ->
              new Countdown(
                  stage,
                  instant(node.node("countdown", "startedAt")),
                  instant(node.node("countdown", "endsAt")));
          case "ending-archiving" -> new Archiving();
          case "intermission" -> new Intermission();
          default -> throw new IOException("season.json has an unknown phase '" + phaseName + "'");
        };
    Set<Duration> sent = new HashSet<>();
    List<String> reminders = node.node("remindersSent").getList(String.class, List.of());
    for (String reminder : reminders) {
      sent.add(Durations.parse(reminder));
    }
    return new SeasonState(
        season,
        phase,
        optional(node.node("plannedEnd"), ZonedTimes::parse),
        optional(node.node("plannedEndSetAt"), Instant::parse),
        sent,
        node.node("autoEndStarted").getBoolean(false),
        optional(node.node("launchedAt"), Instant::parse),
        optional(node.node("homecomingAt"), Instant::parse),
        optional(node.node("countdownInterrupted"), Instant::parse),
        node.node("initSeason").virtual()
            ? Optional.empty()
            : Optional.of(node.node("initSeason").getInt()),
        node.node("launchApplied").getBoolean(true),
        decorating(node.node("decorating")),
        firsts(node.node("firsts")),
        zones(node.node("zones")),
        dragon(node.node("dragon")),
        awards(node.node("awards")));
  }

  private static List<SeasonAwards> awards(ConfigurationNode node) throws IOException {
    List<SeasonAwards> seasons = new java.util.ArrayList<>();
    try {
      for (ConfigurationNode entry : node.childrenList()) {
        List<SeasonAwards.Award> awards = new java.util.ArrayList<>();
        for (ConfigurationNode a : entry.node("awards").childrenList()) {
          List<SeasonAwards.Winner> winners = new java.util.ArrayList<>();
          for (ConfigurationNode w : a.node("winners").childrenList()) {
            winners.add(
                new SeasonAwards.Winner(
                    new PlayerId(UUID.fromString(w.node("player").getString(""))),
                    w.node("name").getString("")));
          }
          awards.add(
              new SeasonAwards.Award(
                  a.node("id").getString(""),
                  a.node("measure").getString(""),
                  a.node("value").getLong(0),
                  winners));
        }
        Optional<SeasonAwards.Pending> pending = Optional.empty();
        ConfigurationNode p = entry.node("pending");
        if (!p.virtual()) {
          pending =
              Optional.of(
                  new SeasonAwards.Pending(
                      p.node("first").getBoolean(false),
                      players(p.node("players")),
                      p.node("announced").getBoolean(false)));
        }
        seasons.add(
            new SeasonAwards(
                entry.node("season").getInt(),
                instant(entry.node("at")),
                awards,
                players(entry.node("counted")),
                pending));
      }
    } catch (IllegalArgumentException e) {
      throw new IOException("season.json has damaged awards", e);
    }
    return seasons;
  }

  private static Set<PlayerId> players(ConfigurationNode node) throws IOException {
    Set<PlayerId> players = new HashSet<>();
    for (String id : node.getList(String.class, List.of())) {
      players.add(new PlayerId(UUID.fromString(id)));
    }
    return players;
  }

  private static void dragon(ConfigurationNode node, DragonState dragon)
      throws SerializationException {
    node.node("flagPending").set(dragon.flagPending());
    if (dragon.fight().isPresent()) {
      DragonState.Fight fight = dragon.fight().get();
      node.node("fight", "dragon").set(fight.dragon().toString());
      for (DragonState.Dealt dealt : fight.dealt()) {
        ConfigurationNode entry = node.node("fight", "dealt").appendListNode();
        entry.node("player").set(dealt.player().toString());
        entry.node("amount").set(dealt.amount());
      }
    }
    if (dragon.kill().isPresent()) {
      DragonState.Kill kill = dragon.kill().get();
      ConfigurationNode at = node.node("kill");
      if (kill.slayer().isPresent()) {
        at.node("slayer").set(kill.slayer().get().toString());
      }
      at.node("name").set(kill.name());
      at.node("at").set(kill.at().toString());
      at.node("egg").set(kill.egg().name().toLowerCase(Locale.ROOT));
      setBlock(at, "eggBefore", kill.eggBefore());
      setBlock(at, "eggAt", kill.eggAt());
    }
  }

  private static DragonState dragon(ConfigurationNode node) throws IOException {
    try {
      Optional<DragonState.Fight> fight = Optional.empty();
      if (!node.node("fight").virtual()) {
        List<DragonState.Dealt> dealt = new java.util.ArrayList<>();
        for (ConfigurationNode entry : node.node("fight", "dealt").childrenList()) {
          dealt.add(
              new DragonState.Dealt(
                  new PlayerId(UUID.fromString(entry.node("player").getString(""))),
                  entry.node("amount").getDouble()));
        }
        fight =
            Optional.of(
                new DragonState.Fight(
                    UUID.fromString(node.node("fight", "dragon").getString("")), dealt));
      }
      Optional<DragonState.Kill> kill = Optional.empty();
      if (!node.node("kill").virtual()) {
        ConfigurationNode at = node.node("kill");
        kill =
            Optional.of(
                new DragonState.Kill(
                    optional(at.node("slayer"), text -> new PlayerId(UUID.fromString(text))),
                    at.node("name").getString(""),
                    instant(at.node("at")),
                    DragonState.Egg.valueOf(
                        at.node("egg").getString("none").toUpperCase(Locale.ROOT)),
                    block(at.node("eggBefore")),
                    block(at.node("eggAt"))));
      }
      return new DragonState(fight, kill, node.node("flagPending").getBoolean(false));
    } catch (IllegalArgumentException e) {
      throw new IOException("season.json has a damaged dragon", e);
    }
  }

  /** A block position as {@code [dimension, x, y, z]}. */
  private static void setBlock(ConfigurationNode node, String key, Optional<Position> block)
      throws SerializationException {
    if (block.isPresent()) {
      Position at = block.get();
      node.node(key, "dimension").set(at.dimension());
      node.node(key, "pos").setList(Integer.class, List.of(at.blockX(), at.blockY(), at.blockZ()));
    }
  }

  private static Optional<Position> block(ConfigurationNode node) throws SerializationException {
    if (node.virtual()) {
      return Optional.empty();
    }
    List<Integer> pos = node.node("pos").getList(Integer.class, List.of());
    if (pos.size() != 3) {
      throw new IllegalArgumentException("a block position needs x, y and z");
    }
    return Optional.of(
        Position.of(node.node("dimension").getString(""), pos.get(0), pos.get(1), pos.get(2)));
  }

  private static ZoneState zones(ConfigurationNode node) throws IOException {
    Map<String, Boolean> unlocked = new HashMap<>();
    for (Map.Entry<Object, ? extends ConfigurationNode> zone :
        node.node("unlocked").childrenMap().entrySet()) {
      unlocked.put(String.valueOf(zone.getKey()), zone.getValue().getBoolean(false));
    }
    Set<PlayerId> reached = new HashSet<>();
    try {
      for (String id : node.node("reachedEnd").getList(String.class, List.of())) {
        reached.add(new PlayerId(UUID.fromString(id)));
      }
    } catch (IllegalArgumentException e) {
      throw new IOException("season.json has a damaged zones.reachedEnd", e);
    }
    return new ZoneState(unlocked, node.node("dragonKilled").getBoolean(false), reached);
  }

  private static List<SeasonFirst> firsts(ConfigurationNode node) throws IOException {
    List<SeasonFirst> firsts = new java.util.ArrayList<>();
    for (ConfigurationNode entry : node.childrenList()) {
      try {
        firsts.add(
            new SeasonFirst(
                entry.node("season").getInt(),
                entry.node("key").getString(""),
                entry.node("label").getString(""),
                new PlayerId(UUID.fromString(entry.node("player").getString(""))),
                entry.node("name").getString(""),
                instant(entry.node("at"))));
      } catch (IllegalArgumentException e) {
        throw new IOException("season.json has a damaged firsts entry", e);
      }
    }
    return firsts;
  }

  private static Decorating decorating(ConfigurationNode node) throws IOException {
    Map<PlayerId, Instant> lockAt = new HashMap<>();
    for (Map.Entry<Object, ? extends ConfigurationNode> lock :
        node.node("lockAt").childrenMap().entrySet()) {
      try {
        lockAt.put(
            new PlayerId(UUID.fromString(String.valueOf(lock.getKey()))), instant(lock.getValue()));
      } catch (IllegalArgumentException e) {
        throw new IOException("season.json has a damaged decorating.lockAt", e);
      }
    }
    return new Decorating(
        optional(node.node("openedAt"), Instant::parse),
        node.node("open").getBoolean(false),
        lockAt);
  }

  private static GsonConfigurationLoader loader(@Nullable StringWriter out) {
    GsonConfigurationLoader.Builder builder = GsonConfigurationLoader.builder().indent(2);
    if (out != null) {
      builder.sink(() -> new BufferedWriter(out));
    }
    return builder.build();
  }

  private static void setInstant(ConfigurationNode node, String key, Optional<Instant> value)
      throws SerializationException {
    if (value.isPresent()) {
      node.node(key).set(value.get().toString());
    }
  }

  private static Instant instant(ConfigurationNode node) throws IOException {
    String text = node.getString();
    if (text == null) {
      throw new IOException("season.json is missing " + node.path());
    }
    return Instant.parse(text);
  }

  private static <T> Optional<T> optional(ConfigurationNode node, Function<String, T> parse) {
    String text = node.getString();
    return text == null || text.isBlank() ? Optional.empty() : Optional.of(parse.apply(text));
  }

  private static String name(Stage stage) {
    return stage.name().toLowerCase(Locale.ROOT);
  }

  private static Stage stage(String name) throws IOException {
    try {
      return Stage.valueOf(name.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IOException("season.json has an unknown stage '" + name + "'");
    }
  }
}
