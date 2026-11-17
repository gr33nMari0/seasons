// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.archive;

import com.petepeterepeat.seasons.core.config.SeasonsConfig.EnderChestPolicy;
import com.petepeterepeat.seasons.core.player.FurthestBlock;
import com.petepeterepeat.seasons.core.port.AdvancementSnapshot;
import com.petepeterepeat.seasons.core.port.Experience;
import com.petepeterepeat.seasons.core.port.InventorySnapshot;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.Skin;
import com.petepeterepeat.seasons.core.store.AtomicFiles;
import com.petepeterepeat.seasons.core.store.nbt.Nbt;
import com.petepeterepeat.seasons.core.store.nbt.Nbt.NbtList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Archive files: one NBT compound per player and season, gzipped like vanilla's own files. Each
 * item is stored as its vanilla compound, so a plain NBT reader opens the whole file, and the root
 * carries the {@code DataVersion} the items were written with. Writes are atomic and fsynced, and
 * run on the writer thread.
 */
public final class ArchiveFiles {
  /** The {@code format-version} this build reads and writes. */
  public static final int FORMAT_VERSION = 1;

  private final Path directory;

  /**
   * @param directory {@code <world>/seasons/data/archives}
   */
  public ArchiveFiles(Path directory) {
    this.directory = directory;
  }

  public Path path(PlayerId player, int season) {
    return directory.resolve("s" + season).resolve(player + ".dat");
  }

  public boolean exists(PlayerId player, int season) {
    return Files.exists(path(player, season));
  }

  /** Writes a record atomically and fsyncs it. */
  public void write(ArchiveRecord record, int dataVersion) throws IOException {
    AtomicFiles.write(path(record.player(), record.season()), encode(record, dataVersion));
  }

  /** Writes a record beside the archive under another name, such as displaced items. */
  public Path writeBeside(ArchiveRecord record, int dataVersion, String suffix) throws IOException {
    Path file =
        path(record.player(), record.season())
            .resolveSibling(record.player() + "." + suffix + ".dat");
    AtomicFiles.write(file, encode(record, dataVersion));
    return file;
  }

  public Optional<ArchiveRecord> read(PlayerId player, int season) throws IOException {
    Path file = path(player, season);
    if (Files.notExists(file)) {
      return Optional.empty();
    }
    return Optional.of(decode(Files.readAllBytes(file)));
  }

  /** Moves an archive aside, as {@code /season unarchive} does. Nothing is deleted. */
  public Path moveAside(PlayerId player, int season, String suffix) throws IOException {
    Path file = path(player, season);
    Path aside = file.resolveSibling(player + "." + suffix + ".dat");
    Files.move(file, aside, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    return aside;
  }

  static byte[] encode(ArchiveRecord record, int dataVersion) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("format-version", FORMAT_VERSION);
    root.put("DataVersion", dataVersion);
    root.put("player", record.player().toString());
    root.put("name", record.name());
    root.put("season", record.season());
    root.put("created", record.created().toEpochMilli());
    root.put("contentHash", record.contentHash());
    root.put("enderChestPolicy", record.enderChestPolicy().name().toLowerCase(Locale.ROOT));
    InventorySnapshot inventory = record.inventory();
    root.put("storage", slots(inventory.storage()));
    root.put("armour", slots(inventory.armour()));
    root.put("offHand", slots(List.of(inventory.offHand())));
    root.put("enderChest", slots(inventory.enderChest()));
    root.put("overflow", slots(record.overflow()));
    root.put("storageSize", inventory.storage().size());
    root.put("armourSize", inventory.armour().size());
    root.put("enderChestSize", inventory.enderChest().size());

    Map<String, Object> advancements = new LinkedHashMap<>();
    record
        .advancements()
        .awarded()
        .forEach((id, criteria) -> {
          Map<String, Object> times = new LinkedHashMap<>();
          criteria.forEach((criterion, at) -> times.put(criterion, at.toEpochMilli()));
          advancements.put(id, times);
        });
    root.put("advancements", advancements);
    root.put("advancementsCompleted", record.advancements().completed());
    root.put("advancementsVisible", record.advancements().visible());

    Map<String, Object> xp = new LinkedHashMap<>();
    xp.put("level", record.experience().level());
    xp.put("progress", record.experience().progress());
    xp.put("total", record.experience().total());
    root.put("xp", xp);

    Map<String, Object> statistics = new LinkedHashMap<>();
    record.statistics().forEach(statistics::put);
    root.put("statistics", statistics);

    record
        .furthest()
        .ifPresent(furthest -> {
          Map<String, Object> best = new LinkedHashMap<>();
          furthest.fromSpawn().ifPresent(b -> best.put("fromSpawn", best(b)));
          furthest.fromOrigin().ifPresent(b -> best.put("fromOrigin", best(b)));
          root.put("furthest", best);
        });
    root.put("finalPosition", position(record.finalPosition()));
    record
        .skin()
        .ifPresent(skin -> {
          Map<String, Object> textures = new LinkedHashMap<>();
          textures.put("value", skin.value());
          skin.signature().ifPresent(signature -> textures.put("signature", signature));
          root.put("skin", textures);
        });
    return Nbt.writeCompressed(root);
  }

  @SuppressWarnings("unchecked")
  static ArchiveRecord decode(byte[] bytes) throws IOException {
    Map<String, Object> root = Nbt.read(bytes);
    int version = integer(root, "format-version");
    if (version < 1 || version > FORMAT_VERSION) {
      throw new IOException(
          "Archive has format-version " + version + "; this Seasons reads " + FORMAT_VERSION);
    }
    try {
      PlayerId player = new PlayerId(UUID.fromString(string(root, "player")));
      InventorySnapshot inventory =
          new InventorySnapshot(
              slots(
                  root.getOrDefault("storage", NbtList.ofCompounds(List.of())),
                  integer(root, "storageSize")),
              slots(
                  root.getOrDefault("armour", NbtList.ofCompounds(List.of())),
                  integer(root, "armourSize")),
              slots(root.getOrDefault("offHand", NbtList.ofCompounds(List.of())), 1).getFirst(),
              slots(
                  root.getOrDefault("enderChest", NbtList.ofCompounds(List.of())),
                  integer(root, "enderChestSize")));
      List<Optional<NeutralItem>> overflow =
          slots(root.getOrDefault("overflow", NbtList.ofCompounds(List.of())), -1);

      Map<String, Map<String, Instant>> awarded = new LinkedHashMap<>();
      ((Map<String, Object>) root.getOrDefault("advancements", Map.of()))
          .forEach((id, criteria) -> {
            Map<String, Instant> times = new LinkedHashMap<>();
            ((Map<String, Object>) criteria)
                .forEach((criterion, at) -> times.put(criterion, Instant.ofEpochMilli((Long) at)));
            awarded.put(id, times);
          });
      Map<String, Object> xp = (Map<String, Object>) root.get("xp");
      Map<String, Long> statistics = new LinkedHashMap<>();
      ((Map<String, Object>) root.getOrDefault("statistics", Map.of()))
          .forEach((key, value) -> statistics.put(key, (Long) value));
      Optional<FurthestBlock> furthest =
          Optional.ofNullable((Map<String, Object>) root.get("furthest"))
              .map(map ->
                  new FurthestBlock(
                      Optional.ofNullable((Map<String, Object>) map.get("fromSpawn"))
                          .map(ArchiveFiles::best),
                      Optional.ofNullable((Map<String, Object>) map.get("fromOrigin"))
                          .map(ArchiveFiles::best)));
      Optional<Skin> skin =
          Optional.ofNullable((Map<String, Object>) root.get("skin"))
              .map(map ->
                  new Skin(
                      value(map, "value", String.class),
                      Optional.ofNullable((String) map.get("signature"))));
      return new ArchiveRecord(
          player,
          string(root, "name"),
          integer(root, "season"),
          Instant.ofEpochMilli(value(root, "created", Long.class)),
          inventory,
          overflow,
          EnderChestPolicy.valueOf(string(root, "enderChestPolicy").toUpperCase(Locale.ROOT)),
          new AdvancementSnapshot(
              awarded,
              integer(root, "advancementsCompleted"),
              integer(root, "advancementsVisible")),
          xp == null
              ? Experience.NONE
              : new Experience(
                  value(xp, "level", Integer.class),
                  value(xp, "progress", Float.class),
                  value(xp, "total", Integer.class)),
          statistics,
          furthest,
          position(compound(root, "finalPosition")),
          skin,
          string(root, "contentHash"));
    } catch (ClassCastException | NullPointerException | IllegalArgumentException e) {
      throw new IOException("Archive is damaged: " + e, e);
    }
  }

  /** Slots as a list of {slot, item} compounds; empty slots are left out. */
  static NbtList slots(List<Optional<NeutralItem>> slots) {
    List<Map<String, Object>> entries = new ArrayList<>();
    for (int slot = 0; slot < slots.size(); slot++) {
      Optional<NeutralItem> item = slots.get(slot);
      if (item.isPresent()) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("slot", slot);
        entry.put("item", item(item.get()));
        entries.add(entry);
      }
    }
    return NbtList.ofCompounds(entries);
  }

  /**
   * Slots back from their compounds.
   *
   * @param size how many slots there are, or -1 for exactly as many as the entries say
   */
  @SuppressWarnings("unchecked")
  static List<Optional<NeutralItem>> slots(Object list, int size) {
    List<Map<String, Object>> entries = list instanceof NbtList nbt ? nbt.compounds() : List.of();
    int count = size;
    if (count < 0) {
      count = entries.stream().mapToInt(e -> value(e, "slot", Integer.class) + 1).max().orElse(0);
    }
    List<Optional<NeutralItem>> slots = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      slots.add(Optional.empty());
    }
    for (Map<String, Object> entry : entries) {
      int slot = value(entry, "slot", Integer.class);
      slots.set(slot, Optional.of(new NeutralItem(Nbt.writeCompressed(compound(entry, "item")))));
    }
    return slots;
  }

  private static Map<String, Object> item(NeutralItem item) {
    try {
      return Nbt.read(item.nbt());
    } catch (IOException e) {
      throw new IllegalArgumentException("An item isn't valid NBT", e);
    }
  }

  private static Map<String, Object> best(FurthestBlock.Best best) {
    Map<String, Object> map = position(best.at());
    map.put("distance", best.distance());
    return map;
  }

  private static FurthestBlock.Best best(Map<String, Object> map) {
    return new FurthestBlock.Best(value(map, "distance", Long.class), position(map));
  }

  static Map<String, Object> position(Position position) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("dimension", position.dimension());
    map.put("x", position.x());
    map.put("y", position.y());
    map.put("z", position.z());
    map.put("yaw", position.yaw());
    map.put("pitch", position.pitch());
    return map;
  }

  static Position position(Map<String, Object> map) {
    return new Position(
        value(map, "dimension", String.class),
        value(map, "x", Double.class),
        value(map, "y", Double.class),
        value(map, "z", Double.class),
        value(map, "yaw", Float.class),
        value(map, "pitch", Float.class));
  }

  /** A value that must be there, of a known type; a damaged file throws. */
  static <T> T value(Map<String, Object> map, String key, Class<T> type) {
    Object value = map.get(key);
    if (value == null || !type.isInstance(value)) {
      throw new IllegalArgumentException("missing or wrong " + key);
    }
    return type.cast(value);
  }

  /** A compound that must be there. */
  @SuppressWarnings("unchecked")
  static Map<String, Object> compound(Map<String, Object> map, String key) {
    return (Map<String, Object>) value(map, key, Map.class);
  }

  private static int integer(Map<String, Object> root, String key) throws IOException {
    if (!(root.get(key) instanceof Integer value)) {
      throw new IOException("Archive is missing " + key);
    }
    return value;
  }

  private static String string(Map<String, Object> root, String key) throws IOException {
    if (!(root.get(key) instanceof String value)) {
      throw new IOException("Archive is missing " + key);
    }
    return value;
  }
}
