// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.season;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.petepeterepeat.seasons.core.config.Durations;
import com.petepeterepeat.seasons.core.config.ZonedTimes;
import com.petepeterepeat.seasons.core.port.PlayerId;
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
        firsts(node.node("firsts")));
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
