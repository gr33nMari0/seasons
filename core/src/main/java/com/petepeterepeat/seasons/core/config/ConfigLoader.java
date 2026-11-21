// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import com.petepeterepeat.seasons.core.hall.Gallery;
import com.petepeterepeat.seasons.core.hall.GalleryFiles;
import com.petepeterepeat.seasons.core.hall.GalleryRules;
import com.petepeterepeat.seasons.core.hall.HallFiles;
import com.petepeterepeat.seasons.core.hall.HallLayout;
import com.petepeterepeat.seasons.core.map.Waypoints;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.zone.UnlockCondition;
import com.petepeterepeat.seasons.core.zone.Zone;
import com.petepeterepeat.seasons.core.zone.ZoneFiles;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.ConfigurationOptions;
import org.spongepowered.configurate.loader.ParsingException;
import org.spongepowered.configurate.objectmapping.ObjectMapper;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializerCollection;
import org.spongepowered.configurate.util.NamingSchemes;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Reads and validates every file into one {@link ConfigSnapshot}, or reports every problem found.
 * Nothing here swaps anything in: the caller keeps the previous snapshot when a load has problems.
 */
public final class ConfigLoader {
  public static final String CONFIG = "config.yml";
  public static final String MESSAGES = "messages.yml";
  public static final String SECRETS = "secrets.yml";
  public static final String MARKERS = "markers.yml";
  public static final String HALL = "hall.yml";
  private static final String SECRETS_TEMPLATE = "secrets.template.yml";
  public static final String DISCORD_WEBHOOK_ENV = "SEASONS_DISCORD_WEBHOOK";
  public static final String ADMIN_WEBHOOK_ENV = "SEASONS_ADMIN_WEBHOOK";

  /** The {@code schema-version} this build reads and writes. */
  public static final int SCHEMA_VERSION = 1;

  private static final String SCHEMA_KEY = "schema-version";
  private static final String BUNDLED = "/com/petepeterepeat/seasons/core/";
  private static final Pattern SEASON_FILE = Pattern.compile("(\\d+)\\.yml");
  private static final Pattern MARK = Pattern.compile("line (\\d+), column (\\d+):");
  private static final Pattern AWARD_ID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
  private static final String AWARDS = "awards";

  private final Path configDirectory;
  private final Path seasonsDirectory;
  private final Path galleriesDirectory;
  private final Path markersFile;
  private final Path hallFile;
  private final Map<String, String> environment;
  private final Predicate<String> dimensionExists;
  private final Predicate<String> isItem;
  private final Predicate<String> isGateBlock;

  /**
   * A loader that takes every dimension id as one the server has.
   *
   * @param configDirectory {@code plugins/Seasons}
   * @param worldDataDirectory {@code <world>/seasons}
   * @param environment environment variables, for the webhook overrides
   */
  public ConfigLoader(
      Path configDirectory, Path worldDataDirectory, Map<String, String> environment) {
    this(configDirectory, worldDataDirectory, environment, dimension -> true);
  }

  /**
   * @param dimensionExists whether the server has a dimension, such as {@code
   *     minecraft:the_nether}; a zone in one it doesn't have is a problem, since it would lock
   *     nothing
   */
  public ConfigLoader(
      Path configDirectory,
      Path worldDataDirectory,
      Map<String, String> environment,
      Predicate<String> dimensionExists) {
    this(configDirectory, worldDataDirectory, environment, dimensionExists, id -> true, id -> true);
  }

  /**
   * @param isItem whether the server knows an item, such as {@code minecraft:trident}
   * @param isGateBlock whether a block can stand as a cell's gate, such as {@code
   *     minecraft:iron_bars} (see {@link com.petepeterepeat.seasons.core.port.World#isGateBlock})
   */
  public ConfigLoader(
      Path configDirectory,
      Path worldDataDirectory,
      Map<String, String> environment,
      Predicate<String> dimensionExists,
      Predicate<String> isItem,
      Predicate<String> isGateBlock) {
    this.isItem = isItem;
    this.isGateBlock = isGateBlock;
    this.configDirectory = configDirectory;
    this.seasonsDirectory = worldDataDirectory.resolve("seasons");
    this.galleriesDirectory = worldDataDirectory.resolve("galleries");
    this.markersFile = worldDataDirectory.resolve(MARKERS);
    this.hallFile = worldDataDirectory.resolve(HALL);
    this.environment = Map.copyOf(environment);
    this.dimensionExists = dimensionExists;
  }

  /** What one load found. */
  public record Result(
      Optional<ConfigSnapshot> snapshot, List<ConfigProblem> problems, List<String> warnings) {
    public Result {
      problems = List.copyOf(problems);
      warnings = List.copyOf(warnings);
    }
  }

  /** The bundled defaults, with no secrets and no season files. */
  public static ConfigSnapshot defaults() {
    try {
      ConfigurationNode bundled = bundledNode(CONFIG);
      SeasonsConfig config = map(bundled);
      return new ConfigSnapshot(
          config,
          Messages.defaults(),
          Secrets.none(),
          Map.of(),
          Map.of(),
          Map.of(),
          Waypoints.NONE,
          List.of(),
          settings(bundled, bundled));
    } catch (IOException e) {
      throw new IllegalStateException("The bundled config.yml doesn't load", e);
    }
  }

  /** {@code <world>/seasons/seasons/<n>.yml}. */
  public Path seasonFile(int season) {
    return seasonsDirectory.resolve(season + ".yml");
  }

  /** {@code <world>/seasons/markers.yml}, the shared waypoints. */
  public Path markersFile() {
    return markersFile;
  }

  /** {@code <world>/seasons/hall.yml}, the hub, the Hall spawn, exits and links. */
  public Path hallFile() {
    return hallFile;
  }

  /** {@code plugins/Seasons/config.yml}. */
  public Path configFile() {
    return configDirectory.resolve(CONFIG);
  }

  /** Writes the bundled config.yml, messages.yml and secrets.yml where a server has none. */
  public void writeDefaults() throws IOException {
    Files.createDirectories(configDirectory);
    // The secrets template has its own name: .gitignore keeps every secrets.yml out of the repo.
    Map<String, String> defaults = Map.of(CONFIG, CONFIG, SECRETS, SECRETS_TEMPLATE);
    for (Map.Entry<String, String> entry : defaults.entrySet()) {
      Path file = configDirectory.resolve(entry.getKey());
      if (Files.notExists(file)) {
        try (InputStream in = bundled(entry.getValue())) {
          Files.copy(in, file);
        }
      }
    }
    Messages.load(configDirectory);
  }

  /** Reads and validates every file, for a reload: any problem at all and nothing loads. */
  public Result load() {
    return load(false);
  }

  /**
   * Reads and validates every file at startup, when there is no previous config to keep. A gallery
   * file with problems is left out, with its problems in {@link ConfigSnapshot#galleriesLeftOut},
   * and so is a markers.yml with problems ({@link ConfigSnapshot#markersLeftOut}), so neither can
   * take every other file down with it, the zones included. Any other problem and nothing loads.
   */
  public Result loadAtStartup() {
    return load(true);
  }

  private Result load(boolean atStartup) {
    List<ConfigProblem> problems = new ArrayList<>();
    List<String> warnings = new ArrayList<>();

    Map<String, String> settings = new LinkedHashMap<>();
    SeasonsConfig config = loadConfig(problems, warnings, settings);
    Messages messages = loadMessages(problems);
    Secrets secrets = loadSecrets(problems);
    Map<Integer, SeasonFile> seasons = loadSeasons(problems, warnings);
    List<ConfigProblem> markersLeftOut = new ArrayList<>();
    Waypoints waypoints = loadWaypoints(markersLeftOut);
    Map<String, List<ConfigProblem>> leftOut = new HashMap<>();
    Map<String, Gallery> galleries = loadGalleries(leftOut);
    List<ConfigProblem> hallLeftOut = new ArrayList<>();
    HallLayout hall = loadHall(galleries.values(), hallLeftOut);
    if (!atStartup) {
      problems.addAll(markersLeftOut);
      markersLeftOut.clear();
      leftOut.values().forEach(problems::addAll);
      leftOut.clear();
      problems.addAll(hallLeftOut);
      hallLeftOut.clear();
    }
    warnings.addAll(spawnsInHall(seasons, galleries.values(), hall));

    if (!problems.isEmpty() || config == null || messages == null || secrets == null) {
      return new Result(Optional.empty(), problems, warnings);
    }
    return new Result(
        Optional.of(
            new ConfigSnapshot(
                config,
                messages,
                secrets,
                seasons,
                galleries,
                leftOut,
                waypoints,
                markersLeftOut,
                settings,
                hall,
                hallLeftOut)),
        problems,
        warnings);
  }

  /**
   * hall.yml, or no Hall parts if the server has none yet. One with problems, or that breaks a rule
   * with the galleries, gives none and its problems go in {@code problems} (questions 243).
   */
  private HallLayout loadHall(Collection<Gallery> galleries, List<ConfigProblem> problems) {
    if (Files.notExists(hallFile)) {
      return HallLayout.NONE;
    }
    try {
      ConfigurationNode node = yaml(hallFile).load();
      if (!checkSchema(HALL, node, problems)) {
        return HallLayout.NONE;
      }
      HallLayout hall = HallFiles.read(node);
      List<GalleryRules.Problem> broken = GalleryRules.check(hall, galleries);
      if (broken.isEmpty()) {
        return hall;
      }
      for (GalleryRules.Problem problem : broken) {
        problems.add(ConfigProblem.atPath(HALL, problem.path(), problem.message()));
      }
    } catch (GalleryFiles.Invalid e) {
      problems.add(ConfigProblem.atPath(HALL, e.path(), Objects.requireNonNull(e.getMessage())));
    } catch (ParsingException e) {
      problems.add(syntax(HALL, e));
    } catch (IOException e) {
      problems.add(ConfigProblem.inFile(HALL, String.valueOf(e.getMessage())));
    }
    return HallLayout.NONE;
  }

  /**
   * A season's spawn or spawn candidate inside the Hall: a warning, as one inside a zone is
   * (questions 256).
   */
  private static List<String> spawnsInHall(
      Map<Integer, SeasonFile> seasons, Collection<Gallery> galleries, HallLayout hall) {
    List<String> warnings = new ArrayList<>();
    Predicate<Position> inHall =
        at -> hall.inHub(at) || galleries.stream().anyMatch(g -> g.contains(at));
    for (SeasonFile season : new java.util.TreeMap<>(seasons).values()) {
      String name = "seasons/" + season.season() + ".yml";
      season
          .spawn()
          .filter(inHall)
          .ifPresent(at -> warnings.add(spawnInHall(name, season.season(), at)));
      season
          .spawnCandidates()
          .forEach((candidate, at) -> {
            if (inHall.test(at)) {
              warnings.add(
                  name
                      + ": Season "
                      + season.season()
                      + "'s spawn candidate "
                      + candidate
                      + " at "
                      + xyz(at)
                      + " is inside the Hall of Fame; every candidate must stay outside it");
            }
          });
    }
    return warnings;
  }

  /** The warning for a spawn inside the Hall, as reload and validate report it. */
  public static String spawnInHall(String file, int season, Position at) {
    return file
        + ": Season "
        + season
        + "'s spawn at "
        + xyz(at)
        + " is inside the Hall of Fame; /season launch is refused until it moves";
  }

  private static String xyz(Position at) {
    return at.blockX() + ", " + at.blockY() + ", " + at.blockZ();
  }

  /** {@code <world>/seasons/galleries/<id>.yml}. */
  public Path galleryFile(String id) {
    return galleriesDirectory.resolve(id + ".yml");
  }

  /**
   * Every gallery file that loads and keeps the rules. One that doesn't is left out, with its
   * problems in {@code leftOut} under its id; so is a gallery that breaks a rule with another.
   */
  private Map<String, Gallery> loadGalleries(Map<String, List<ConfigProblem>> leftOut) {
    Map<String, Gallery> galleries = new HashMap<>();
    if (!Files.isDirectory(galleriesDirectory)) {
      return galleries;
    }
    List<Path> files;
    try (Stream<Path> listed = Files.list(galleriesDirectory)) {
      files = listed.filter(p -> p.getFileName().toString().endsWith(".yml")).sorted().toList();
    } catch (IOException e) {
      leftOut
          .computeIfAbsent("", _ -> new ArrayList<>())
          .add(ConfigProblem.inFile("galleries/", "can't be listed: " + e.getMessage()));
      return galleries;
    }
    for (Path file : files) {
      String fileName = file.getFileName().toString();
      String name = "galleries/" + fileName;
      String id = fileName.substring(0, fileName.length() - ".yml".length());
      List<ConfigProblem> problems = new ArrayList<>();
      if (!GalleryFiles.ID.matcher(id).matches()) {
        problems.add(
            ConfigProblem.inFile(
                name, "gallery files are named by id: lower-case letters, digits, - and _"));
      } else {
        try {
          ConfigurationNode node = yaml(file).load();
          if (checkSchema(name, node, problems)) {
            galleries.put(id, GalleryFiles.read(id, node));
          }
        } catch (GalleryFiles.Invalid e) {
          problems.add(
              ConfigProblem.atPath(name, e.path(), Objects.requireNonNull(e.getMessage())));
        } catch (ParsingException e) {
          problems.add(syntax(name, e));
        } catch (IOException e) {
          problems.add(ConfigProblem.inFile(name, String.valueOf(e.getMessage())));
        }
      }
      if (!problems.isEmpty()) {
        leftOut.computeIfAbsent(id, _ -> new ArrayList<>()).addAll(problems);
      }
    }
    // A gallery that breaks a rule is left out too, until the rest keep them all.
    while (true) {
      List<GalleryRules.Problem> broken =
          GalleryRules.check(
              galleries.values().stream()
                  .sorted(java.util.Comparator.comparing(Gallery::id))
                  .toList());
      if (broken.isEmpty()) {
        return galleries;
      }
      String id = broken.getFirst().gallery();
      for (GalleryRules.Problem problem : broken) {
        if (problem.gallery().equals(id)) {
          leftOut
              .computeIfAbsent(id, _ -> new ArrayList<>())
              .add(
                  ConfigProblem.atPath(
                      "galleries/" + id + ".yml", problem.path(), problem.message()));
        }
      }
      galleries.remove(id);
    }
  }

  /** The shared waypoints, or none if the server has no markers.yml yet. */
  private Waypoints loadWaypoints(List<ConfigProblem> problems) {
    if (Files.notExists(markersFile)) {
      return Waypoints.NONE;
    }
    try {
      ConfigurationNode node = yaml(markersFile).load();
      if (checkSchema(MARKERS, node, problems)) {
        return WaypointFiles.read(node, dimensionExists);
      }
    } catch (WaypointFiles.Invalid e) {
      problems.add(ConfigProblem.atPath(MARKERS, e.path(), Objects.requireNonNull(e.getMessage())));
    } catch (ParsingException e) {
      problems.add(syntax(MARKERS, e));
    } catch (IOException e) {
      problems.add(ConfigProblem.inFile(MARKERS, String.valueOf(e.getMessage())));
    }
    return Waypoints.NONE;
  }

  /**
   * config.yml over the bundled defaults.
   *
   * @param settings filled with every setting's value as written, for {@code /season config get}
   */
  private @Nullable SeasonsConfig loadConfig(
      List<ConfigProblem> problems, List<String> warnings, Map<String, String> settings) {
    Path file = configDirectory.resolve(CONFIG);
    try {
      ConfigurationNode node = Files.exists(file) ? yaml(file).load() : yamlNode();
      if (!checkSchema(CONFIG, node, problems)) {
        return null;
      }
      ConfigurationNode defaults = bundledNode(CONFIG);
      unknownKeys(node, defaults, warnings);
      node.mergeFrom(defaults);
      SeasonsConfig config = map(node);
      validate(config, problems);
      checkNames(config, problems);
      settings.putAll(settings(node, defaults));
      return config;
    } catch (ParsingException e) {
      problems.add(syntax(CONFIG, e));
    } catch (SerializationException e) {
      problems.add(ConfigProblem.atPath(CONFIG, path(e), e.rawMessage()));
    } catch (IOException e) {
      problems.add(ConfigProblem.inFile(CONFIG, String.valueOf(e.getMessage())));
    }
    return null;
  }

  private @Nullable Messages loadMessages(List<ConfigProblem> problems) {
    try {
      return Messages.load(configDirectory);
    } catch (ParsingException e) {
      problems.add(syntax(MESSAGES, e));
    } catch (IOException e) {
      problems.add(ConfigProblem.inFile(MESSAGES, String.valueOf(e.getMessage())));
    }
    return null;
  }

  private @Nullable Secrets loadSecrets(List<ConfigProblem> problems) {
    Path file = configDirectory.resolve(SECRETS);
    ConfigurationNode node;
    try {
      node = Files.exists(file) ? yaml(file).load() : yamlNode();
    } catch (ParsingException e) {
      problems.add(syntax(SECRETS, e));
      return null;
    } catch (IOException e) {
      problems.add(ConfigProblem.inFile(SECRETS, "can't be read"));
      return null;
    }
    if (!checkSchema(SECRETS, node, problems)) {
      return null;
    }
    int before = problems.size();
    Optional<URI> discord =
        webhook("discordWebhook", environment.get(DISCORD_WEBHOOK_ENV), node, problems);
    Optional<URI> admin =
        webhook("adminWebhook", environment.get(ADMIN_WEBHOOK_ENV), node, problems);
    return problems.size() == before ? new Secrets(discord, admin) : null;
  }

  /** A webhook URL from the environment or the file. Problems never quote the URL. */
  private static Optional<URI> webhook(
      String key,
      @Nullable String fromEnvironment,
      ConfigurationNode node,
      List<ConfigProblem> problems) {
    String value =
        fromEnvironment != null && !fromEnvironment.isBlank()
            ? fromEnvironment
            : node.node(key).getString("");
    if (value.isBlank()) {
      return Optional.empty();
    }
    try {
      URI uri = new URI(value.trim());
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
        problems.add(ConfigProblem.atPath(SECRETS, key, "a webhook URL must start with https://"));
        return Optional.empty();
      }
      return Optional.of(uri);
    } catch (URISyntaxException e) {
      problems.add(ConfigProblem.atPath(SECRETS, key, "is not a valid URL"));
      return Optional.empty();
    }
  }

  /**
   * Every season file. A spawn inside one of its season's zones is a warning, not a problem: the
   * file still loads, and {@code /season launch} is refused until one of them moves.
   */
  private Map<Integer, SeasonFile> loadSeasons(
      List<ConfigProblem> problems, List<String> warnings) {
    Map<Integer, SeasonFile> seasons = new HashMap<>();
    if (!Files.isDirectory(seasonsDirectory)) {
      return seasons;
    }
    List<Path> files;
    try (Stream<Path> listed = Files.list(seasonsDirectory)) {
      files = listed.filter(p -> p.getFileName().toString().endsWith(".yml")).sorted().toList();
    } catch (IOException e) {
      problems.add(ConfigProblem.inFile("seasons/", "can't be listed: " + e.getMessage()));
      return seasons;
    }
    for (Path file : files) {
      String name = "seasons/" + file.getFileName();
      Matcher matcher = SEASON_FILE.matcher(file.getFileName().toString());
      if (!matcher.matches()) {
        problems.add(ConfigProblem.inFile(name, "season files are named by number, such as 3.yml"));
        continue;
      }
      int season = Integer.parseInt(matcher.group(1));
      try {
        ConfigurationNode node = yaml(file).load();
        if (!checkSchema(name, node, problems)) {
          continue;
        }
        int declared = node.node("season").getInt(season);
        if (declared != season) {
          problems.add(
              ConfigProblem.atPath(
                  name, "season", "says " + declared + " in the file for " + season));
          continue;
        }
        Optional<ZonedDateTime> plannedEnd = Optional.empty();
        String end = node.node("schedule", "plannedEnd").getString();
        if (end != null && !end.isBlank()) {
          try {
            plannedEnd = Optional.of(ZonedTimes.parse(end));
          } catch (IllegalArgumentException e) {
            problems.add(
                ConfigProblem.atPath(
                    name, "schedule.plannedEnd", Objects.requireNonNull(e.getMessage())));
            continue;
          }
        }
        Optional<Position> spawn;
        try {
          spawn = spawn(node.node("spawn"));
        } catch (IllegalArgumentException e) {
          problems.add(ConfigProblem.atPath(name, "spawn", Objects.requireNonNull(e.getMessage())));
          continue;
        }
        Optional<Map<String, Position>> candidates =
            candidates(name, node.node("spawnCandidates"), problems);
        if (candidates.isEmpty()) {
          continue;
        }
        Map<String, Zone> zones;
        UnlockCondition homecoming;
        try {
          zones = ZoneFiles.zones(node.node("zones"));
          homecoming = ZoneFiles.homecoming(node.node("homecoming"));
        } catch (ZoneFiles.Invalid e) {
          problems.add(
              ConfigProblem.atPath(name, e.path(), Objects.requireNonNull(e.getMessage())));
          continue;
        }
        Optional<Zone> elsewhere =
            zones.values().stream().filter(z -> !dimensionExists.test(z.dimension())).findFirst();
        if (elsewhere.isPresent()) {
          Zone zone = elsewhere.get();
          problems.add(
              ConfigProblem.atPath(
                  name,
                  "zones." + zone.id() + ".dimension",
                  "this server has no dimension " + zone.dimension()));
          continue;
        }
        SeasonFile loaded =
            new SeasonFile(season, plannedEnd, spawn, zones, homecoming, candidates.get());
        loaded.zoneHoldingSpawn().ifPresent(zone -> warnings.add(spawnInZone(name, loaded, zone)));
        loaded
            .spawn()
            .filter(SeasonFile::beyondStrongholds)
            .ifPresent(at -> warnings.add(spawnBeyondStrongholds(name, loaded.season(), at)));
        loaded
            .spawnCandidates()
            .forEach((candidate, at) ->
                loaded
                    .zoneHolding(at)
                    .ifPresent(
                        zone -> warnings.add(candidateInZone(name, loaded, candidate, at, zone))));
        seasons.put(season, loaded);
      } catch (ParsingException e) {
        problems.add(syntax(name, e));
      } catch (IOException e) {
        problems.add(ConfigProblem.inFile(name, String.valueOf(e.getMessage())));
      }
    }
    return seasons;
  }

  /** The warning for a spawn inside a zone, as reload and validate report it. */
  public static String spawnInZone(String file, SeasonFile season, Zone zone) {
    Position at = season.spawn().orElseThrow();
    return file
        + ": Season "
        + season.season()
        + "'s spawn at "
        + at.blockX()
        + ", "
        + at.blockY()
        + ", "
        + at.blockZ()
        + " is inside zone "
        + zone.id()
        + "; /season launch is refused until one of them moves";
  }

  /** The warning for a spawn beyond {@link SeasonFile#STRONGHOLD_REACH}. */
  public static String spawnBeyondStrongholds(String file, int season, Position at) {
    return file
        + ": Season "
        + season
        + "'s spawn at "
        + at.blockX()
        + ", "
        + at.blockY()
        + ", "
        + at.blockZ()
        + " is "
        + SeasonFile.distanceFromOrigin(at)
        + " blocks from 0, 0, beyond 24,000; strongholds may be out of reach";
  }

  /** The warning for a spawn candidate inside a zone, as reload and validate report it. */
  public static String candidateInZone(
      String file, SeasonFile season, String candidate, Position at, Zone zone) {
    return file
        + ": Season "
        + season.season()
        + "'s spawn candidate "
        + candidate
        + " at "
        + at.blockX()
        + ", "
        + at.blockY()
        + ", "
        + at.blockZ()
        + " is inside zone "
        + zone.id()
        + "; every candidate must stay outside every zone";
  }

  /**
   * A season file's spawn candidates, each written as a spawn is, by name; empty after adding a
   * problem for any that doesn't make sense, or is in a dimension the server doesn't have.
   */
  private Optional<Map<String, Position>> candidates(
      String file, ConfigurationNode node, List<ConfigProblem> problems) {
    Map<String, Position> candidates = new HashMap<>();
    int before = problems.size();
    for (Map.Entry<Object, ? extends ConfigurationNode> entry : node.childrenMap().entrySet()) {
      String name = String.valueOf(entry.getKey());
      String path = "spawnCandidates." + name;
      if (!SeasonFile.CANDIDATE_NAME.matcher(name).matches()) {
        problems.add(
            ConfigProblem.atPath(
                file, path, "candidate names are lower-case letters, digits and dashes"));
        continue;
      }
      Optional<Position> at;
      try {
        at = spawn(entry.getValue());
      } catch (IllegalArgumentException e) {
        problems.add(ConfigProblem.atPath(file, path, String.valueOf(e.getMessage())));
        continue;
      }
      if (at.isEmpty()) {
        problems.add(ConfigProblem.atPath(file, path, "pos needs three numbers, [x, y, z]"));
      } else if (!dimensionExists.test(at.get().dimension())) {
        problems.add(
            ConfigProblem.atPath(
                file, path + ".dimension", "this server has no dimension " + at.get().dimension()));
      } else {
        candidates.put(name, at.get());
      }
    }
    return problems.size() == before ? Optional.of(candidates) : Optional.empty();
  }

  /**
   * A spawn written as {@code { dimension: minecraft:overworld, pos: [x, y, z], yaw: 180 }}, or
   * none if the key is missing.
   */
  static Optional<Position> spawn(ConfigurationNode node) {
    if (node.virtual() || node.isNull()) {
      return Optional.empty();
    }
    String dimension = node.node("dimension").getString("minecraft:overworld");
    if (!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
      throw new IllegalArgumentException("'" + dimension + "' is not a dimension id");
    }
    List<? extends ConfigurationNode> pos = node.node("pos").childrenList();
    if (pos.size() != 3) {
      throw new IllegalArgumentException("pos needs three numbers, [x, y, z]");
    }
    double[] xyz = new double[3];
    for (int i = 0; i < 3; i++) {
      Object raw = pos.get(i).raw();
      if (!(raw instanceof Number number)) {
        throw new IllegalArgumentException("pos needs three numbers, [x, y, z]");
      }
      xyz[i] = number.doubleValue();
    }
    float yaw = (float) node.node("yaw").getDouble(0);
    return Optional.of(new Position(dimension, xyz[0], xyz[1], xyz[2], yaw, 0f));
  }

  /** Rules a parsed config must also meet. */
  static void validate(SeasonsConfig config, List<ConfigProblem> problems) {
    if (!config.schedule().endCountdown().isPositive()) {
      problems.add(ConfigProblem.atPath(CONFIG, "schedule.endCountdown", "must be longer than 0"));
    }
    for (Duration reminder : config.schedule().reminders()) {
      if (!reminder.isPositive()) {
        problems.add(
            ConfigProblem.atPath(
                CONFIG, "schedule.reminders", "every reminder must be longer than 0"));
      }
    }
    if (!config.launch().requireManual()) {
      // Nothing launches by itself (questions 176).
      problems.add(
          ConfigProblem.atPath(
              CONFIG,
              "launch.requireManual",
              "only true is built: launch is by /season launch or the launch button"));
    }
    int pets = config.pets().maxPerPlayer();
    if (pets < 0 || pets > 10) {
      problems.add(
          ConfigProblem.atPath(
              CONFIG, "pets.maxPerPlayer", "must be between 0 and 10, not " + pets));
    }
    String display = config.deaths().display();
    if (!SeasonsConfig.Deaths.SLOTS.contains(display)) {
      problems.add(
          ConfigProblem.atPath(
              CONFIG,
              "deaths.display",
              "must be list, sidebar, below_name or none, not " + display));
    }
    config
        .awards()
        .forEach((id, award) -> {
          String path = "awards." + id;
          if (!AWARD_ID.matcher(id).matches()) {
            problems.add(
                ConfigProblem.atPath(
                    CONFIG,
                    path,
                    "award ids are lower-case letters, digits and dashes, such as most-deaths"));
          }
          if (award.measure() == null) {
            problems.add(ConfigProblem.atPath(CONFIG, path + ".measure", "is missing"));
          } else if (award.parsed().isEmpty()) {
            problems.add(
                ConfigProblem.atPath(
                    CONFIG,
                    path + ".measure",
                    award.measure() + " isn't a measure: use " + AwardMeasure.choices()));
          } else if (award.on() && award.item() == null) {
            problems.add(ConfigProblem.atPath(CONFIG, path + ".item", "is missing"));
          }
        });
  }

  /**
   * "Item groups name known items" (questions 175): every rare item and cell container is an item
   * the server knows, and the gate block one that can stand as a gate: solid, not falling, holding
   * nothing. Ids without a namespace are Minecraft's.
   */
  private void checkNames(SeasonsConfig config, List<ConfigProblem> problems) {
    for (String item : config.firsts().rareItems()) {
      if (!isItem.test(namespaced(item))) {
        problems.add(
            ConfigProblem.atPath(
                CONFIG, "firsts.rareItems", item + " isn't an item this server knows"));
      }
    }
    for (String item : config.cells().containers()) {
      if (!isItem.test(namespaced(item))) {
        problems.add(
            ConfigProblem.atPath(
                CONFIG, "cells.containers", item + " isn't an item this server knows"));
      }
    }
    config
        .awards()
        .forEach((id, award) -> {
          String item = award.item();
          if (item != null && !isItem.test(namespaced(item))) {
            problems.add(
                ConfigProblem.atPath(
                    CONFIG, "awards." + id + ".item", item + " isn't an item this server knows"));
          }
        });
    if (!isItem.test(namespaced(config.wand().item()))) {
      problems.add(
          ConfigProblem.atPath(
              CONFIG, "wand.item", config.wand().item() + " isn't an item this server knows"));
    }
    String gate = config.cells().gateBlock();
    if (!isGateBlock.test(namespaced(gate))) {
      problems.add(
          ConfigProblem.atPath(
              CONFIG,
              "cells.gateBlock",
              gate
                  + " can't be a gate: it must be a solid block that doesn't fall and holds"
                  + " nothing"));
    }
  }

  private static String namespaced(String id) {
    return id.indexOf(':') < 0 ? "minecraft:" + id : id;
  }

  private static boolean checkSchema(
      String file, ConfigurationNode node, List<ConfigProblem> problems) {
    int version = node.node(SCHEMA_KEY).getInt(SCHEMA_VERSION);
    if (version > SCHEMA_VERSION) {
      problems.add(
          ConfigProblem.atPath(
              file,
              SCHEMA_KEY,
              "is "
                  + version
                  + ", newer than this Seasons reads ("
                  + SCHEMA_VERSION
                  + "); update Seasons or restore a backup"));
      return false;
    }
    return true;
  }

  /**
   * Every setting the bundled config.yml has, in its order, by key path, with its value in {@code
   * merged} as text: a list as {@code [a, b]}. Keys only a server's file has are left out.
   */
  static Map<String, String> settings(ConfigurationNode merged, ConfigurationNode defaults) {
    Map<String, String> out = new LinkedHashMap<>();
    collect(merged, defaults, out);
    out.remove(SCHEMA_KEY);
    return out;
  }

  private static void collect(
      ConfigurationNode merged, ConfigurationNode defaults, Map<String, String> out) {
    for (Map.Entry<Object, ? extends ConfigurationNode> child : defaults.childrenMap().entrySet()) {
      ConfigurationNode value = merged.node(child.getKey());
      if (child.getValue().isMap()) {
        collect(value, child.getValue(), out);
      } else {
        out.put(dotted(child.getValue()), text(value));
      }
    }
    if (isAwards(defaults)) {
      // A server's own awards are settings too, shaped like the bundled ones.
      ConfigurationNode shape = defaults.childrenMap().values().iterator().next();
      for (Map.Entry<Object, ? extends ConfigurationNode> child : merged.childrenMap().entrySet()) {
        if (defaults.node(child.getKey()).virtual()) {
          for (Object key : shape.childrenMap().keySet()) {
            out.put(dotted(child.getValue()) + "." + key, text(child.getValue().node(key)));
          }
        }
      }
    }
  }

  /** The awards section, whose ids are a server's to choose. */
  private static boolean isAwards(ConfigurationNode node) {
    return node.path().size() == 1
        && AWARDS.equals(node.key())
        && node.isMap()
        && !node.childrenMap().isEmpty();
  }

  /** A setting's value as admins read it. */
  private static String text(ConfigurationNode node) {
    if (node.isList()) {
      List<String> items = new ArrayList<>();
      for (ConfigurationNode item : node.childrenList()) {
        items.add(text(item));
      }
      return "[" + String.join(", ", items) + "]";
    }
    return String.valueOf(node.raw());
  }

  /** Keys in a server's file that the bundled defaults don't have: a warning, not an error. */
  private static void unknownKeys(
      ConfigurationNode node, ConfigurationNode defaults, List<String> warnings) {
    if (!node.isMap()) {
      return;
    }
    node.childrenMap()
        .forEach((key, child) -> {
          ConfigurationNode known = defaults.node(key);
          if (known.virtual() && isAwards(defaults)) {
            // An award of the server's own: its keys are checked against a bundled award's.
            unknownKeys(child, defaults.childrenMap().values().iterator().next(), warnings);
          } else if (known.virtual()) {
            warnings.add(CONFIG + ": unknown key " + dotted(child) + " is ignored");
          } else if (known.isMap()) {
            unknownKeys(child, known, warnings);
          }
        });
  }

  /**
   * A syntax error with its position. Configurate's YAML loader leaves the position in the message
   * of the parser's exception it wraps, so it is read from there.
   */
  static ConfigProblem syntax(String file, ParsingException e) {
    if (e.line() > 0) {
      return ConfigProblem.at(file, e.line(), Math.max(1, e.column()), e.rawMessage().strip());
    }
    for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
      String text = cause.getMessage();
      if (text == null) {
        continue;
      }
      List<String> lines = text.lines().toList();
      int mark = -1;
      Matcher position = null;
      for (int i = 0; i < lines.size(); i++) {
        Matcher m = MARK.matcher(lines.get(i));
        if (m.find()) {
          mark = i;
          position = m;
        }
      }
      if (position == null || mark < 1) {
        continue;
      }
      String problem = lines.get(mark - 1).strip();
      String context = lines.getFirst().strip();
      String message =
          context.equals(problem) || MARK.matcher(context).find()
              ? problem
              : context + ": " + problem;
      return ConfigProblem.at(
          file, Integer.parseInt(position.group(1)), Integer.parseInt(position.group(2)), message);
    }
    return ConfigProblem.inFile(file, "is not valid YAML");
  }

  private static String path(ConfigurateException e) {
    StringBuilder out = new StringBuilder();
    for (Object part : e.path()) {
      if (!out.isEmpty()) {
        out.append('.');
      }
      out.append(part);
    }
    return out.toString();
  }

  private static String dotted(ConfigurationNode node) {
    StringBuilder out = new StringBuilder();
    for (Object part : node.path()) {
      if (!out.isEmpty()) {
        out.append('.');
      }
      out.append(part);
    }
    return out.toString();
  }

  static SeasonsConfig map(ConfigurationNode node) throws SerializationException {
    SeasonsConfig config = node.get(SeasonsConfig.class);
    if (config == null) {
      throw new SerializationException("config.yml is empty");
    }
    return config;
  }

  private static ConfigurationOptions options() {
    TypeSerializerCollection serializers =
        TypeSerializerCollection.defaults()
            .childBuilder()
            .register(Duration.class, DurationSerializer.INSTANCE)
            .register(DashedEnumSerializer::handles, DashedEnumSerializer.INSTANCE)
            .registerAnnotatedObjects(
                ObjectMapper.factoryBuilder()
                    .defaultNamingScheme(NamingSchemes.PASSTHROUGH)
                    .build())
            .build();
    return ConfigurationOptions.defaults().serializers(serializers);
  }

  private static YamlConfigurationLoader yaml(Path file) {
    return YamlConfigurationLoader.builder().path(file).defaultOptions(options()).build();
  }

  private static ConfigurationNode yamlNode() {
    return YamlConfigurationLoader.builder().defaultOptions(options()).build().createNode();
  }

  private static ConfigurationNode bundledNode(String name) throws ConfigurateException {
    return YamlConfigurationLoader.builder()
        .source(
            () -> new BufferedReader(new InputStreamReader(bundled(name), StandardCharsets.UTF_8)))
        .defaultOptions(options())
        .build()
        .load();
  }

  private static InputStream bundled(String name) {
    return Objects.requireNonNull(
        ConfigLoader.class.getResourceAsStream(BUNDLED + name), "bundled " + name + " is missing");
  }
}
