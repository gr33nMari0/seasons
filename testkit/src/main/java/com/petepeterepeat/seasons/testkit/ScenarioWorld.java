// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.testkit;

import com.petepeterepeat.seasons.core.scenario.ScenarioHooks;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.KeyedBossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Returns a shared scenario server to what a fresh boot looks like between scenarios, and checks
 * that it did (docs/adr/0003).
 *
 * <p>Seasons starts again as a fresh install through its scenario hook. Everything else is the
 * server's: players are kicked and their files deleted; ops and the whitelist cleared; scoreboards,
 * command boss bars, forced chunks and this plugin's chunk tickets removed; game rules, time,
 * weather, difficulty, world border and spawn put back; every block in a chunk loaded since the
 * last reset put back as it was generated, in every dimension; and every entity but players
 * removed.
 *
 * <p>Blocks come back from a copy of each chunk taken the moment it was generated, kept on disk in
 * the shard's state folder with the list of chunks loaded since the last reset, so both survive a
 * restart or a halt. After the reset the server is compared with the fingerprint taken at the
 * shard's fresh boot; any difference is a leak, reported by name.
 */
final class ScenarioWorld implements Listener {
  private static final String SEASONS = "Seasons";

  private final Plugin plugin;
  private final Server server;
  private final Path state;
  private final Path pristine;
  private final Path touchedLog;
  private final Map<String, BlockData> parsed = new HashMap<>();
  private final Set<String> touched = new LinkedHashSet<>();

  ScenarioWorld(Plugin plugin, Path state) {
    this.plugin = plugin;
    this.server = plugin.getServer();
    this.state = state;
    this.pristine = state.resolve("pristine");
    this.touchedLog = state.resolve("touched.log");
    try {
      Files.createDirectories(pristine);
      if (Files.exists(touchedLog)) {
        touched.addAll(Files.readAllLines(touchedLog));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Copies a chunk the moment it is generated, and notes every chunk loaded. */
  @EventHandler
  void onChunkLoad(ChunkLoadEvent event) {
    Chunk chunk = event.getChunk();
    if (event.isNewChunk()) {
      keepPristine(chunk);
    }
    touch(chunk.getWorld(), chunk.getX(), chunk.getZ());
  }

  /**
   * On a fresh boot, before any scenario: copies every chunk already loaded (generated before this
   * plugin could listen), removes what the world generated besides blocks, such as animals, and
   * writes the fingerprint later resets are compared with.
   */
  void freshBoot() {
    for (World world : server.getWorlds()) {
      for (Chunk chunk : world.getLoadedChunks()) {
        if (!Files.exists(pristineFile(world, chunk.getX(), chunk.getZ()))) {
          keepPristine(chunk);
        }
        touch(world, chunk.getX(), chunk.getZ());
      }
      removeEntities(world);
    }
    write(state.resolve("baseline.txt"), render(fingerprint()));
    write(state.resolve("rules.txt"), render(worldRules()));
  }

  /**
   * Resets the server between scenarios, over a few ticks, and completes on the server thread with
   * the differences from a fresh boot: empty when the reset left nothing behind.
   */
  CompletableFuture<List<String>> reset(ScenarioHooks hooks, Runnable beforeSeasons) {
    CompletableFuture<List<String>> done = new CompletableFuture<>();
    for (Player player : server.getOnlinePlayers()) {
      player.kick(Component.text("Scenario reset"));
    }
    whenEveryoneLeft(
        100,
        () -> {
          try {
            beforeSeasons.run();
            clearScoreboard();
            hooks.reset();
            List<String> unrestored = new ArrayList<>();
            restoreWorlds(unrestored);
            clearServerLists();
            deletePlayerFiles();
            List<String> leaks = new ArrayList<>(unrestored);
            leaks.addAll(differences(read(state.resolve("baseline.txt")), fingerprint(hooks)));
            done.complete(leaks);
          } catch (RuntimeException e) {
            done.completeExceptionally(e);
          }
        });
    return done;
  }

  private void whenEveryoneLeft(int ticksLeft, Runnable then) {
    server
        .getGlobalRegionScheduler()
        .runDelayed(
            plugin,
            task -> {
              if (server.getOnlinePlayers().isEmpty() || ticksLeft <= 0) {
                then.run();
              } else {
                whenEveryoneLeft(ticksLeft - 1, then);
              }
            },
            1);
  }

  private void clearScoreboard() {
    Scoreboard board = server.getScoreboardManager().getMainScoreboard();
    board.getObjectives().forEach(Objective::unregister);
    board.getTeams().forEach(Team::unregister);
    for (String entry : board.getEntries()) {
      board.resetScores(entry);
    }
  }

  private void restoreWorlds(List<String> unrestored) {
    Map<String, String> rules = read(state.resolve("rules.txt"));
    for (World world : server.getWorlds()) {
      world.getForceLoadedChunks().forEach(c -> c.setForceLoaded(false));
      world.removePluginChunkTickets(plugin);
      Set<String> chunks = new LinkedHashSet<>();
      String prefix = world.getKey() + " ";
      touched.stream().filter(t -> t.startsWith(prefix)).forEach(chunks::add);
      for (Chunk chunk : world.getLoadedChunks()) {
        chunks.add(prefix + chunk.getX() + " " + chunk.getZ());
      }
      for (String key : chunks) {
        String[] parts = key.split(" ", -1);
        Chunk chunk = world.getChunkAt(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        if (!restore(world, chunk)) {
          unrestored.add("blocks: chunk " + key + " differs from when it was generated");
        }
      }
      removeEntities(world);
      putBackRules(world, rules);
    }
    touched.clear();
    try {
      Files.deleteIfExists(touchedLog);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void removeEntities(World world) {
    for (Entity entity : world.getEntities()) {
      if (!(entity instanceof Player)) {
        entity.remove();
      }
    }
  }

  /** Game rules, time, weather, difficulty, border and spawn, as {@code world.key=value}. */
  private Map<String, String> worldRules() {
    Map<String, String> rules = new TreeMap<>();
    for (World world : server.getWorlds()) {
      String w = world.getKey().toString();
      for (GameRule<?> rule : Registry.GAME_RULE) {
        if (!world.isEnabled(rule)) {
          continue; // behind a feature flag this world doesn't have
        }
        Object value = world.getGameRuleValue(rule);
        if (value != null) {
          rules.put(w + ".rule." + rule.getKey(), value.toString());
        }
      }
      rules.put(w + ".time", Long.toString(world.getFullTime()));
      rules.put(w + ".storm", Boolean.toString(world.hasStorm()));
      rules.put(w + ".thundering", Boolean.toString(world.isThundering()));
      rules.put(w + ".weatherDuration", Integer.toString(world.getWeatherDuration()));
      rules.put(w + ".thunderDuration", Integer.toString(world.getThunderDuration()));
      rules.put(w + ".clearWeatherDuration", Integer.toString(world.getClearWeatherDuration()));
      rules.put(w + ".difficulty", world.getDifficulty().name());
      rules.put(w + ".border", border(world));
      rules.put(w + ".spawn", spawn(world.getSpawnLocation()));
    }
    return rules;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void putBackRules(World world, Map<String, String> rules) {
    String w = world.getKey().toString();
    for (GameRule rule : Registry.GAME_RULE) {
      String value = rules.get(w + ".rule." + rule.getKey());
      if (value == null) {
        continue;
      }
      Class<?> type = rule.getType();
      Object parsed =
          type == Boolean.class
              ? Boolean.valueOf(value)
              : type == Integer.class ? Integer.valueOf(value) : value;
      world.setGameRule(rule, parsed);
    }
    if (world.getEnvironment() == World.Environment.NORMAL) {
      world.setFullTime(Long.parseLong(rules.getOrDefault(w + ".time", "0")));
    }
    world.setStorm(Boolean.parseBoolean(rules.get(w + ".storm")));
    world.setThundering(Boolean.parseBoolean(rules.get(w + ".thundering")));
    world.setWeatherDuration(Integer.parseInt(rules.getOrDefault(w + ".weatherDuration", "0")));
    world.setThunderDuration(Integer.parseInt(rules.getOrDefault(w + ".thunderDuration", "0")));
    world.setClearWeatherDuration(
        Integer.parseInt(rules.getOrDefault(w + ".clearWeatherDuration", "0")));
    world.setDifficulty(org.bukkit.Difficulty.valueOf(required(rules, w + ".difficulty")));
    String[] border = required(rules, w + ".border").split(" ", -1);
    world.getWorldBorder().setCenter(Double.parseDouble(border[0]), Double.parseDouble(border[1]));
    world.getWorldBorder().setSize(Double.parseDouble(border[2]));
    String[] spawn = required(rules, w + ".spawn").split(" ", -1);
    world.setSpawnLocation(
        Integer.parseInt(spawn[0]), Integer.parseInt(spawn[1]), Integer.parseInt(spawn[2]));
  }

  private static String required(Map<String, String> values, String key) {
    String value = values.get(key);
    if (value == null) {
      throw new IllegalStateException("The fresh boot recorded no " + key);
    }
    return value;
  }

  private static String border(World world) {
    var border = world.getWorldBorder();
    return border.getCenter().getX() + " " + border.getCenter().getZ() + " " + border.getSize();
  }

  private static String spawn(Location at) {
    return at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ();
  }

  private void clearServerLists() {
    for (OfflinePlayer op : server.getOperators()) {
      op.setOp(false);
    }
    for (OfflinePlayer listed : server.getWhitelistedPlayers()) {
      listed.setWhitelisted(false);
    }
    // Bans are left alone: no scenario bans anyone, and the leak check would see one.
    List<KeyedBossBar> bars = new ArrayList<>();
    server.getBossBars().forEachRemaining(bars::add);
    for (KeyedBossBar bar : bars) {
      bar.removeAll();
      server.removeBossBar(bar.getKey());
    }
  }

  private void deletePlayerFiles() {
    Path players = playersFolder();
    for (String kind : List.of("data", "advancements", "stats")) {
      Path dir = players.resolve(kind);
      if (!Files.isDirectory(dir)) {
        continue;
      }
      try (Stream<Path> files = Files.list(dir)) {
        for (Path file : files.toList()) {
          Files.delete(file);
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  private Path playersFolder() {
    return server
        .getWorldContainer()
        .toPath()
        .resolve(server.getWorlds().getFirst().getName())
        .resolve("players");
  }

  // Blocks

  private void touch(World world, int x, int z) {
    String key = world.getKey() + " " + x + " " + z;
    if (touched.add(key)) {
      try {
        Files.writeString(
            touchedLog,
            key + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
            StandardOpenOption.WRITE);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  private Path pristineFile(World world, int x, int z) {
    return pristine.resolve(world.getKey().toString().replace(':', '_')).resolve(x + "_" + z);
  }

  /**
   * Writes a chunk's blocks, section by section: whether the section is empty, else its palette of
   * block states and an index into it for each of its 4096 blocks.
   */
  private void keepPristine(Chunk chunk) {
    World world = chunk.getWorld();
    ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
    int sections = (world.getMaxHeight() - world.getMinHeight()) >> 4;
    Path file = pristineFile(world, chunk.getX(), chunk.getZ());
    try {
      Files.createDirectories(file.getParent());
      try (DataOutputStream out =
          new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(file)))) {
        out.writeInt(sections);
        for (int s = 0; s < sections; s++) {
          if (snapshot.isSectionEmpty(s)) {
            out.writeBoolean(true);
            continue;
          }
          out.writeBoolean(false);
          Map<String, Integer> palette = new java.util.LinkedHashMap<>();
          short[] indices = new short[4096];
          int baseY = world.getMinHeight() + (s << 4);
          for (int i = 0; i < 4096; i++) {
            String block =
                snapshot.getBlockData(i & 15, baseY + (i >> 8), (i >> 4) & 15).getAsString();
            indices[i] = (short) (int) palette.computeIfAbsent(block, b -> palette.size());
          }
          out.writeInt(palette.size());
          for (String block : palette.keySet()) {
            out.writeUTF(block);
          }
          for (short index : indices) {
            out.writeShort(index);
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Puts back every block of a chunk that differs from its copy, without physics, and checks it now
   * matches. Returns false if there is no copy or it still differs.
   */
  private boolean restore(World world, Chunk chunk) {
    Path file = pristineFile(world, chunk.getX(), chunk.getZ());
    if (!Files.exists(file)) {
      return false;
    }
    ChunkSnapshot now = chunk.getChunkSnapshot(false, false, false);
    boolean changed = false;
    try (DataInputStream in =
        new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(file)))) {
      int sections = in.readInt();
      for (int s = 0; s < sections; s++) {
        int baseY = world.getMinHeight() + (s << 4);
        if (in.readBoolean()) {
          if (!now.isSectionEmpty(s)) {
            changed |= putBack(chunk, now, baseY, null, null);
          }
          continue;
        }
        BlockData[] palette = new BlockData[in.readInt()];
        for (int p = 0; p < palette.length; p++) {
          palette[p] = parsed.computeIfAbsent(in.readUTF(), server::createBlockData);
        }
        short[] indices = new short[4096];
        for (int i = 0; i < 4096; i++) {
          indices[i] = in.readShort();
        }
        changed |= putBack(chunk, now, baseY, palette, indices);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (!changed) {
      return true;
    }
    ChunkSnapshot after = chunk.getChunkSnapshot(false, false, false);
    try (DataInputStream in =
        new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(file)))) {
      int sections = in.readInt();
      for (int s = 0; s < sections; s++) {
        int baseY = world.getMinHeight() + (s << 4);
        if (in.readBoolean()) {
          if (!after.isSectionEmpty(s)) {
            return false;
          }
          continue;
        }
        BlockData[] palette = new BlockData[in.readInt()];
        for (int p = 0; p < palette.length; p++) {
          palette[p] = parsed.computeIfAbsent(in.readUTF(), server::createBlockData);
        }
        for (int i = 0; i < 4096; i++) {
          BlockData want = palette[in.readShort()];
          if (!after.getBlockData(i & 15, baseY + (i >> 8), (i >> 4) & 15).equals(want)) {
            return false;
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return true;
  }

  /** Sets back the blocks of one section that differ; {@code palette} null means all air. */
  private boolean putBack(
      Chunk chunk,
      ChunkSnapshot now,
      int baseY,
      BlockData @org.jspecify.annotations.Nullable [] palette,
      short @org.jspecify.annotations.Nullable [] indices) {
    BlockData air = parsed.computeIfAbsent("minecraft:air", server::createBlockData);
    boolean changed = false;
    for (int i = 0; i < 4096; i++) {
      int x = i & 15;
      int y = baseY + (i >> 8);
      int z = (i >> 4) & 15;
      BlockData want = palette == null || indices == null ? air : palette[indices[i]];
      if (!now.getBlockData(x, y, z).equals(want)) {
        chunk.getBlock(x, y, z).setBlockData(want, false);
        changed = true;
      }
    }
    return changed;
  }

  // Fingerprint

  /** What a fresh boot looks like, as {@code name=value}, compared after every reset. */
  private Map<String, String> fingerprint() {
    return fingerprint(null);
  }

  private Map<String, String> fingerprint(@org.jspecify.annotations.Nullable ScenarioHooks hooks) {
    Map<String, String> print = new TreeMap<>(worldRules());
    print.put("players.online", Integer.toString(server.getOnlinePlayers().size()));
    print.put("players.ops", names(server.getOperators()));
    print.put("players.whitelisted", names(server.getWhitelistedPlayers()));
    print.put("players.banned", names(server.getBannedPlayers()));
    print.put("players.ipBans", String.join(",", new java.util.TreeSet<>(server.getIPBans())));
    print.put("players.files", playerFiles());
    print.put(
        "worlds",
        server.getWorlds().stream()
            .map(w -> w.getKey().toString())
            .collect(Collectors.joining(",")));
    Scoreboard board = server.getScoreboardManager().getMainScoreboard();
    print.put(
        "scoreboard.objectives",
        board.getObjectives().stream()
            .map(Objective::getName)
            .sorted()
            .collect(Collectors.joining(",")));
    print.put(
        "scoreboard.teams",
        board.getTeams().stream().map(Team::getName).sorted().collect(Collectors.joining(",")));
    print.put("scoreboard.entries", Integer.toString(board.getEntries().size()));
    List<String> bars = new ArrayList<>();
    server.getBossBars().forEachRemaining(bar -> bars.add(bar.getKey().toString()));
    print.put("bossBars", String.join(",", bars));
    for (World world : server.getWorlds()) {
      String w = world.getKey().toString();
      print.put(
          w + ".entities",
          world.getEntities().stream()
              .filter(e -> !(e instanceof Player))
              .map(e -> e.getType().getKey().toString())
              .sorted()
              .collect(Collectors.joining(",")));
      print.put(w + ".forced", Integer.toString(world.getForceLoadedChunks().size()));
      print.put(
          w + ".tickets",
          world.getPluginChunkTickets().entrySet().stream()
              .map(e -> e.getKey().getName() + ":" + e.getValue().size())
              .sorted()
              .collect(Collectors.joining(",")));
    }
    // The time moves on by itself; it is put back by the reset, and compared there.
    print.keySet().removeIf(k -> k.endsWith(".time") || k.endsWith("Duration"));
    Path seasonsData = server.getWorlds().getFirst().getWorldFolder().toPath().resolve("seasons");
    print.put("seasons.config", tree(server.getPluginsFolder().toPath().resolve(SEASONS)));
    print.put("seasons.data", tree(seasonsData));
    if (hooks != null) {
      print.put("seasons.tasks", Integer.toString(hooks.scheduledTasks()));
    } else {
      ScenarioHooks found = server.getServicesManager().load(ScenarioHooks.class);
      print.put("seasons.tasks", found == null ? "?" : Integer.toString(found.scheduledTasks()));
    }
    return print;
  }

  private static String names(Set<OfflinePlayer> players) {
    return players.stream()
        .map(p -> String.valueOf(p.getUniqueId()))
        .sorted()
        .collect(Collectors.joining(","));
  }

  private String playerFiles() {
    Path players = playersFolder();
    if (!Files.isDirectory(players)) {
      return "";
    }
    try (Stream<Path> files = Files.walk(players)) {
      return files
          .filter(Files::isRegularFile)
          .map(f -> players.relativize(f).toString())
          .sorted()
          .collect(Collectors.joining(","));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Every file under a folder with a hash of its contents, or {@code none}. */
  private static String tree(Path root) {
    if (!Files.isDirectory(root)) {
      return "none";
    }
    try (Stream<Path> files = Files.walk(root)) {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      return files
          .filter(Files::isRegularFile)
          .sorted(Comparator.comparing(Path::toString))
          .map(f -> {
            try {
              byte[] hash = sha.digest(Files.readAllBytes(f));
              return root.relativize(f) + "#" + HexFormat.of().formatHex(hash, 0, 4);
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          })
          .collect(Collectors.joining(","));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<String> differences(Map<String, String> fresh, Map<String, String> now) {
    List<String> leaks = new ArrayList<>();
    Set<String> keys = new java.util.TreeSet<>(fresh.keySet());
    keys.addAll(now.keySet());
    for (String key : keys) {
      String was = fresh.getOrDefault(key, "(absent)");
      String is = now.getOrDefault(key, "(absent)");
      if (!was.equals(is)) {
        leaks.add(key + ": fresh boot " + abbreviate(was) + ", after reset " + abbreviate(is));
      }
    }
    return leaks;
  }

  private static String abbreviate(String value) {
    String shown = value.isEmpty() ? "(empty)" : value;
    return shown.length() > 300 ? shown.substring(0, 300) + "…" : shown;
  }

  private static String render(Map<String, String> values) {
    return values.entrySet().stream()
        .map(e -> e.getKey() + "=" + e.getValue().replace("\n", " "))
        .collect(Collectors.joining("\n", "", "\n"));
  }

  private static Map<String, String> read(Path file) {
    Map<String, String> values = new TreeMap<>();
    try {
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        int at = line.indexOf('=');
        if (at > 0) {
          values.put(line.substring(0, at), line.substring(at + 1));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return values;
  }

  private static void write(Path file, String text) {
    try {
      Files.writeString(file, text);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
