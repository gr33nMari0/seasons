// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.player;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.petepeterepeat.seasons.core.port.GameMode;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.gson.GsonConfigurationLoader;
import org.spongepowered.configurate.serialize.SerializationException;

/**
 * Player records, loaded once at startup and cached. Changes are saved through the writer thread:
 * at most once per {@link #COALESCE} for a busy player, and at once at every journal step.
 */
public final class PlayerStore {
  /** The {@code format-version} this build reads and writes. */
  public static final int FORMAT_VERSION = 1;

  /** How often changed records are written when nothing forces a write sooner. */
  public static final Duration COALESCE = Duration.ofSeconds(30);

  private static final Logger LOG = LoggerFactory.getLogger(PlayerStore.class);

  private final Path directory;
  private final SerialWriter writer;
  private final Map<PlayerId, PlayerRecord> records = new ConcurrentHashMap<>();
  private final Set<PlayerId> dirty = ConcurrentHashMap.newKeySet();

  /**
   * @param directory {@code <world>/seasons/data/players}
   */
  public PlayerStore(Path directory, SerialWriter writer) {
    this.directory = directory;
    this.writer = writer;
  }

  /**
   * Loads every record, on the writer thread, so joins never wait on disk. A record that can't be
   * read is logged and skipped; its file is left alone for an admin to look at.
   */
  public CompletableFuture<Integer> loadAll() {
    return writer.submit(() -> {
      if (Files.notExists(directory)) {
        return 0;
      }
      List<Path> files;
      try (Stream<Path> listed = Files.list(directory)) {
        files = listed.filter(p -> p.getFileName().toString().endsWith(".json")).toList();
      }
      int loaded = 0;
      for (Path file : files) {
        try {
          PlayerRecord record = decode(Files.readString(file, UTF_8));
          records.put(record.id(), record);
          loaded++;
        } catch (IOException | RuntimeException e) {
          LOG.error("Can't read player record {}; it is skipped until fixed", file, e);
        }
      }
      return loaded;
    });
  }

  public Optional<PlayerRecord> find(PlayerId id) {
    return Optional.ofNullable(records.get(id));
  }

  /** A player's record, made if they have none. The new record isn't saved until changed. */
  public PlayerRecord getOrCreate(PlayerId id, String name) {
    PlayerRecord record = records.get(id);
    return record != null ? record : PlayerRecord.create(id, name);
  }

  public Collection<PlayerRecord> all() {
    return List.copyOf(records.values());
  }

  /** Replaces a record in memory; it is written with the next {@link #flush}. */
  public void put(PlayerRecord record) {
    records.put(record.id(), record);
    dirty.add(record.id());
  }

  /** Replaces a record and writes it now; the future completes once it is on disk. */
  public CompletableFuture<Void> putNow(PlayerRecord record) {
    records.put(record.id(), record);
    dirty.remove(record.id());
    String json = encode(record);
    Path file = path(record.id());
    return writer
        .submit(() -> {
          AtomicFiles.write(file, json.getBytes(UTF_8));
          return Boolean.TRUE;
        })
        .thenApply(done -> null);
  }

  /** Writes every changed record. */
  public CompletableFuture<Void> flush() {
    List<PlayerId> changed = List.copyOf(dirty);
    dirty.removeAll(changed);
    CompletableFuture<?>[] saves =
        changed.stream()
            .map(records::get)
            .filter(java.util.Objects::nonNull)
            .map(this::putNow)
            .toArray(CompletableFuture[]::new);
    return CompletableFuture.allOf(saves);
  }

  private Path path(PlayerId id) {
    return directory.resolve(id + ".json");
  }

  static String encode(PlayerRecord record) {
    try {
      GsonConfigurationLoader loader = GsonConfigurationLoader.builder().indent(2).build();
      ConfigurationNode node = loader.createNode();
      node.node("format-version").set(FORMAT_VERSION);
      node.node("uuid").set(record.id().toString());
      node.node("lastName").set(record.lastName());
      for (Map.Entry<Integer, PlayerRecord.SeasonPlay> entry : record.seasons().entrySet()) {
        ConfigurationNode play = node.node("seasons", entry.getKey().toString());
        play.node("firstSeen").set(entry.getValue().firstSeen().toString());
        play.node("lastSeen").set(entry.getValue().lastSeen().toString());
        play.node("playTimeSeconds").set(entry.getValue().playTime().toSeconds());
      }
      node.node("archivedThrough").set(record.archivedThrough());
      node.node("seasonEntered").set(record.seasonEntered());
      for (Map.Entry<Integer, Map<String, Long>> entry : record.baselines().entrySet()) {
        ConfigurationNode baseline = node.node("baselines", entry.getKey().toString());
        entry.getValue().forEach((stat, value) -> setUnchecked(baseline.node(stat), value));
      }
      for (Map.Entry<Integer, FurthestBlock> entry : record.furthest().entrySet()) {
        ConfigurationNode furthest = node.node("furthest", entry.getKey().toString());
        entry.getValue().fromSpawn().ifPresent(best -> best(furthest.node("fromSpawn"), best));
        entry.getValue().fromOrigin().ifPresent(best -> best(furthest.node("fromOrigin"), best));
      }
      node.node("pendingDisplays")
          .setList(Integer.class, record.pendingDisplays().stream().sorted().toList());
      if (record.pendingDestination().isPresent()) {
        node.node("pendingDestination").set(record.pendingDestination().getAsInt());
      }
      if (record.hallAdventure()) {
        node.node("hallAdventure").set(true);
      }
      if (record.hallReturn().isPresent()) {
        PlayerRecord.HallReturn back = record.hallReturn().get();
        ConfigurationNode at = node.node("hallReturn");
        at.node("dimension").set(back.at().dimension());
        at.node("x").set(back.at().x());
        at.node("y").set(back.at().y());
        at.node("z").set(back.at().z());
        at.node("yaw").set(back.at().yaw());
        at.node("pitch").set(back.at().pitch());
        at.node("gameMode").set(back.mode().name().toLowerCase(java.util.Locale.ROOT));
      }
      for (PlayerRecord.Pet pet : record.pets()) {
        ConfigurationNode entry = node.node("pets").appendListNode();
        entry.node("entity").set(pet.entity().toString());
        entry.node("type").set(pet.type());
        entry.node("name").set(pet.name());
        entry.node("season").set(pet.season());
        if (pet.lastSeen().isPresent()) {
          Position at = pet.lastSeen().get();
          ConfigurationNode seen = entry.node("lastSeen");
          seen.node("dimension").set(at.dimension());
          seen.node("x").set(at.x());
          seen.node("y").set(at.y());
          seen.node("z").set(at.z());
        }
      }
      for (PlayerRecord.Notice notice : record.notices()) {
        ConfigurationNode entry = node.node("notices").appendListNode();
        entry.node("key").set(notice.key());
        entry.node("placeholders").setList(String.class, notice.placeholders());
      }
      StringWriter out = new StringWriter();
      GsonConfigurationLoader.builder()
          .indent(2)
          .sink(() -> new BufferedWriter(out))
          .build()
          .save(node);
      return out.toString();
    } catch (IOException e) {
      throw new IllegalStateException("Can't encode the record of " + record.id(), e);
    }
  }

  static PlayerRecord decode(String json) throws IOException {
    ConfigurationNode node =
        GsonConfigurationLoader.builder()
            .source(() -> new BufferedReader(new StringReader(json)))
            .build()
            .load();
    int version = node.node("format-version").getInt(0);
    if (version < 1 || version > FORMAT_VERSION) {
      throw new IOException(
          "player record has format-version " + version + "; this Seasons reads " + FORMAT_VERSION);
    }
    String uuid = node.node("uuid").getString();
    if (uuid == null) {
      throw new IOException("player record has no uuid");
    }
    Map<Integer, PlayerRecord.SeasonPlay> seasons = new HashMap<>();
    for (Map.Entry<Object, ? extends ConfigurationNode> entry :
        node.node("seasons").childrenMap().entrySet()) {
      ConfigurationNode play = entry.getValue();
      seasons.put(
          Integer.parseInt(entry.getKey().toString()),
          new PlayerRecord.SeasonPlay(
              Instant.parse(play.node("firstSeen").getString("")),
              Instant.parse(play.node("lastSeen").getString("")),
              Duration.ofSeconds(play.node("playTimeSeconds").getLong(0))));
    }
    Map<Integer, Map<String, Long>> baselines = new HashMap<>();
    for (Map.Entry<Object, ? extends ConfigurationNode> entry :
        node.node("baselines").childrenMap().entrySet()) {
      Map<String, Long> values = new HashMap<>();
      entry
          .getValue()
          .childrenMap()
          .forEach((stat, value) -> values.put(stat.toString(), value.getLong()));
      baselines.put(Integer.parseInt(entry.getKey().toString()), values);
    }
    Map<Integer, FurthestBlock> furthest = new HashMap<>();
    for (Map.Entry<Object, ? extends ConfigurationNode> entry :
        node.node("furthest").childrenMap().entrySet()) {
      furthest.put(
          Integer.parseInt(entry.getKey().toString()),
          new FurthestBlock(
              best(entry.getValue().node("fromSpawn")), best(entry.getValue().node("fromOrigin"))));
    }
    Set<Integer> pendingDisplays =
        new HashSet<>(node.node("pendingDisplays").getList(Integer.class, List.of()));
    ConfigurationNode destination = node.node("pendingDestination");
    return new PlayerRecord(
        new PlayerId(UUID.fromString(uuid)),
        node.node("lastName").getString(""),
        seasons,
        node.node("archivedThrough").getInt(0),
        node.node("seasonEntered").getInt(0),
        baselines,
        furthest,
        pendingDisplays,
        destination.virtual() ? OptionalInt.empty() : OptionalInt.of(destination.getInt()),
        node.node("hallAdventure").getBoolean(false),
        hallReturn(node.node("hallReturn")),
        pets(node.node("pets")),
        notices(node.node("notices")));
  }

  private static List<PlayerRecord.Pet> pets(ConfigurationNode node) {
    List<PlayerRecord.Pet> pets = new java.util.ArrayList<>();
    for (ConfigurationNode entry : node.childrenList()) {
      ConfigurationNode seen = entry.node("lastSeen");
      pets.add(
          new PlayerRecord.Pet(
              UUID.fromString(entry.node("entity").getString("")),
              entry.node("type").getString(""),
              entry.node("name").getString(""),
              seen.virtual()
                  ? Optional.empty()
                  : Optional.of(
                      new Position(
                          seen.node("dimension").getString(FurthestBlock.OVERWORLD),
                          seen.node("x").getDouble(),
                          seen.node("y").getDouble(),
                          seen.node("z").getDouble(),
                          0,
                          0)),
              entry.node("season").getInt()));
    }
    return pets;
  }

  private static List<PlayerRecord.Notice> notices(ConfigurationNode node)
      throws SerializationException {
    List<PlayerRecord.Notice> notices = new java.util.ArrayList<>();
    for (ConfigurationNode entry : node.childrenList()) {
      notices.add(
          new PlayerRecord.Notice(
              entry.node("key").getString(""),
              entry.node("placeholders").getList(String.class, List.of())));
    }
    return notices;
  }

  private static Optional<PlayerRecord.HallReturn> hallReturn(ConfigurationNode node) {
    if (node.virtual()) {
      return Optional.empty();
    }
    GameMode mode;
    try {
      mode =
          GameMode.valueOf(
              node.node("gameMode").getString("survival").toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      mode = GameMode.SURVIVAL;
    }
    return Optional.of(
        new PlayerRecord.HallReturn(
            new Position(
                node.node("dimension").getString(FurthestBlock.OVERWORLD),
                node.node("x").getDouble(),
                node.node("y").getDouble(),
                node.node("z").getDouble(),
                node.node("yaw").getFloat(),
                node.node("pitch").getFloat()),
            mode));
  }

  private static void best(ConfigurationNode node, FurthestBlock.Best best) {
    setUnchecked(node.node("distance"), best.distance());
    setUnchecked(node.node("dimension"), best.at().dimension());
    setUnchecked(node.node("x"), best.at().blockX());
    setUnchecked(node.node("y"), best.at().blockY());
    setUnchecked(node.node("z"), best.at().blockZ());
  }

  private static Optional<FurthestBlock.Best> best(ConfigurationNode node) {
    if (node.virtual()) {
      return Optional.empty();
    }
    return Optional.of(
        new FurthestBlock.Best(
            node.node("distance").getLong(),
            Position.of(
                node.node("dimension").getString(FurthestBlock.OVERWORLD),
                node.node("x").getInt(),
                node.node("y").getInt(),
                node.node("z").getInt())));
  }

  private static void setUnchecked(ConfigurationNode node, Object value) {
    try {
      node.set(value);
    } catch (SerializationException e) {
      throw new IllegalStateException(e);
    }
  }
}
