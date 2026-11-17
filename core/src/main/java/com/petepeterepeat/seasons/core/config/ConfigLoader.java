// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Position;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
  private static final String SECRETS_TEMPLATE = "secrets.template.yml";
  public static final String DISCORD_WEBHOOK_ENV = "SEASONS_DISCORD_WEBHOOK";
  public static final String ADMIN_WEBHOOK_ENV = "SEASONS_ADMIN_WEBHOOK";

  /** The {@code schema-version} this build reads and writes. */
  public static final int SCHEMA_VERSION = 1;

  private static final String SCHEMA_KEY = "schema-version";
  private static final String BUNDLED = "/com/petepeterepeat/seasons/core/";
  private static final Pattern SEASON_FILE = Pattern.compile("(\\d+)\\.yml");
  private static final Pattern MARK = Pattern.compile("line (\\d+), column (\\d+):");

  private final Path configDirectory;
  private final Path seasonsDirectory;
  private final Map<String, String> environment;

  /**
   * @param configDirectory {@code plugins/Seasons}
   * @param worldDataDirectory {@code <world>/seasons}
   * @param environment environment variables, for the webhook overrides
   */
  public ConfigLoader(
      Path configDirectory, Path worldDataDirectory, Map<String, String> environment) {
    this.configDirectory = configDirectory;
    this.seasonsDirectory = worldDataDirectory.resolve("seasons");
    this.environment = Map.copyOf(environment);
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
      SeasonsConfig config = map(bundledNode(CONFIG));
      return new ConfigSnapshot(config, Messages.defaults(), Secrets.none(), Map.of());
    } catch (IOException e) {
      throw new IllegalStateException("The bundled config.yml doesn't load", e);
    }
  }

  /** {@code <world>/seasons/seasons/<n>.yml}. */
  public Path seasonFile(int season) {
    return seasonsDirectory.resolve(season + ".yml");
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

  /** Reads and validates every file. */
  public Result load() {
    List<ConfigProblem> problems = new ArrayList<>();
    List<String> warnings = new ArrayList<>();

    SeasonsConfig config = loadConfig(problems, warnings);
    Messages messages = loadMessages(problems);
    Secrets secrets = loadSecrets(problems);
    Map<Integer, SeasonFile> seasons = loadSeasons(problems);

    if (!problems.isEmpty() || config == null || messages == null || secrets == null) {
      return new Result(Optional.empty(), problems, warnings);
    }
    return new Result(
        Optional.of(new ConfigSnapshot(config, messages, secrets, seasons)), problems, warnings);
  }

  private @Nullable SeasonsConfig loadConfig(List<ConfigProblem> problems, List<String> warnings) {
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

  private Map<Integer, SeasonFile> loadSeasons(List<ConfigProblem> problems) {
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
        seasons.put(season, new SeasonFile(season, plannedEnd, spawn));
      } catch (ParsingException e) {
        problems.add(syntax(name, e));
      } catch (IOException e) {
        problems.add(ConfigProblem.inFile(name, String.valueOf(e.getMessage())));
      }
    }
    return seasons;
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
    int pets = config.pets().maxPerPlayer();
    if (pets < 0 || pets > 10) {
      problems.add(
          ConfigProblem.atPath(
              CONFIG, "pets.maxPerPlayer", "must be between 0 and 10, not " + pets));
    }
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

  /** Keys in a server's file that the bundled defaults don't have: a warning, not an error. */
  private static void unknownKeys(
      ConfigurationNode node, ConfigurationNode defaults, List<String> warnings) {
    if (!node.isMap()) {
      return;
    }
    node.childrenMap()
        .forEach((key, child) -> {
          ConfigurationNode known = defaults.node(key);
          if (known.virtual()) {
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
