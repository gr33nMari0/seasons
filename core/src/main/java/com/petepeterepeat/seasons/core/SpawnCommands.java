// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static com.petepeterepeat.seasons.core.Seasons.PERMISSION_ADMIN_SEASON;

import com.petepeterepeat.seasons.core.command.ArgumentType;
import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandHelp;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.command.Positions;
import com.petepeterepeat.seasons.core.config.ConfigEdits;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.config.ConfigLoader;
import com.petepeterepeat.seasons.core.config.SeasonFile;
import com.petepeterepeat.seasons.core.config.SeasonFiles;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.season.SeasonService;
import com.petepeterepeat.seasons.core.zone.Zone;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * {@code /season spawn}: the next season's spawn and its candidates, the places the group votes on.
 * Both go in the next season's file through {@link ConfigEdits}, so an edit is in force at once and
 * audited; neither may be inside one of that file's zones.
 */
final class SpawnCommands {
  private final Platform platform;
  private final ConfigHolder config;
  private final ConfigLoader loader;
  private final ConfigEdits edits;
  private final SeasonService seasons;
  private final Path backups;
  private final CommandHelp help;
  private final java.util.function.BiConsumer<PlayerId, Position> go;

  /**
   * @param backups {@code <world>/seasons/backups/seasons}
   */
  SpawnCommands(
      Platform platform,
      ConfigHolder config,
      ConfigLoader loader,
      ConfigEdits edits,
      SeasonService seasons,
      Path backups,
      CommandHelp help,
      java.util.function.BiConsumer<PlayerId, Position> go) {
    this.platform = platform;
    this.config = config;
    this.loader = loader;
    this.edits = edits;
    this.seasons = seasons;
    this.backups = backups;
    this.help = help;
    this.go = go;
  }

  /**
   * {@code /season tp}: an admin's own teleport to a spawn, candidate or waypoint (questions 271).
   */
  CommandNode tpNode() {
    return CommandNode.literal("tp")
        .permission(PERMISSION_ADMIN_SEASON)
        .then(
            CommandNode.literal("spawn")
                .executes(this::tpSpawn)
                .then(
                    CommandNode.argument("season", ArgumentType.integer(1, Integer.MAX_VALUE))
                        .executes(this::tpSpawn)))
        .then(
            CommandNode.literal("candidate")
                .then(
                    CommandNode.argument("name", ArgumentType.word()).executes(this::tpCandidate)))
        .then(
            CommandNode.literal("marker")
                .then(
                    CommandNode.argument("set", ArgumentType.word())
                        .then(
                            CommandNode.argument("name", ArgumentType.greedy())
                                .executes(this::tpMarker))))
        .build();
  }

  void tpSpawn(CommandSource source, Arguments arguments) {
    if (source.player().isEmpty()) {
      source.reply(messages().get("hof.player-only"));
      return;
    }
    OptionalInt next = nextSeason();
    if (next.isEmpty()) {
      source.reply(messages().get("season.none"));
      return;
    }
    int season =
        arguments.values().containsKey("season")
            ? arguments.integer("season")
            : next.getAsInt() - 1;
    Optional<Position> spawn = config.get().season(season).flatMap(SeasonFile::spawn);
    if (spawn.isEmpty()) {
      source.reply(messages().get("spawn.show-none", "season", Integer.toString(season)));
      return;
    }
    go.accept(source.player().get(), middle(spawn.get()));
  }

  void tpCandidate(CommandSource source, Arguments arguments) {
    if (source.player().isEmpty()) {
      source.reply(messages().get("hof.player-only"));
      return;
    }
    OptionalInt next = nextSeason();
    if (next.isEmpty()) {
      source.reply(messages().get("season.none"));
      return;
    }
    String name = arguments.string("name");
    Optional<Position> at =
        config
            .get()
            .season(next.getAsInt())
            .flatMap(f -> Optional.ofNullable(f.spawnCandidates().get(name)));
    if (at.isEmpty()) {
      source.reply(unknown(next.getAsInt(), name));
      return;
    }
    go.accept(source.player().get(), middle(at.get()));
  }

  void tpMarker(CommandSource source, Arguments arguments) {
    if (source.player().isEmpty()) {
      source.reply(messages().get("hof.player-only"));
      return;
    }
    String set = arguments.string("set");
    String name = arguments.string("name").strip();
    Optional<com.petepeterepeat.seasons.core.map.Waypoint> found =
        config.get().waypoints().find(set, name);
    if (found.isEmpty()) {
      source.reply(messages().get("marker.unknown", "set", set, "name", name));
      return;
    }
    go.accept(source.player().get(), middle(found.get().at()));
  }

  private static Position middle(Position block) {
    return new Position(
        block.dimension(),
        block.blockX() + 0.5,
        block.blockY(),
        block.blockZ() + 0.5,
        block.yaw(),
        0f);
  }

  /** The {@code spawn} node under {@code /season}. */
  CommandNode node() {
    return CommandNode.literal("spawn")
        .permission(PERMISSION_ADMIN_SEASON)
        .then(
            CommandNode.literal("set")
                .executes(this::set)
                .then(
                    CommandNode.literal("at")
                        .then(
                            CommandNode.argument("at", ArgumentType.greedy()).executes(this::set)))
                .then(CommandNode.argument("candidate", ArgumentType.word()).executes(this::set)))
        .then(
            CommandNode.literal("show")
                .executes(this::show)
                .then(
                    CommandNode.argument("season", ArgumentType.integer(1, Integer.MAX_VALUE))
                        .executes(this::show)))
        .then(CommandNode.literal("clear").executes(this::clear))
        .then(
            CommandNode.literal("candidate")
                .then(
                    CommandNode.literal("add")
                        .then(
                            CommandNode.argument("name", ArgumentType.word())
                                .executes(this::add)
                                .then(
                                    CommandNode.argument("at", ArgumentType.greedy())
                                        .executes(this::add))))
                .then(
                    CommandNode.literal("move")
                        .then(
                            CommandNode.argument("name", ArgumentType.word())
                                .executes(this::move)
                                .then(
                                    CommandNode.argument("at", ArgumentType.greedy())
                                        .executes(this::move))))
                .then(
                    CommandNode.literal("remove")
                        .then(
                            CommandNode.argument("name", ArgumentType.word())
                                .executes(this::remove)))
                .then(CommandNode.literal("list").executes(this::list)))
        .build();
  }

  // The spawn

  /** {@code set}, where the admin stands, or {@code set <candidate>}, from anywhere. */
  void set(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    Optional<String> candidate = arguments.optionalString("candidate");
    Optional<String> typed = arguments.optionalString("at");
    if (candidate.isEmpty() && typed.isEmpty() && source.player().isEmpty()) {
      source.reply(messages.get("spawn.player-only"));
      return;
    }
    OptionalInt next = nextSeason();
    if (next.isEmpty()) {
      source.reply(messages.get("season.none"));
      return;
    }
    int season = next.getAsInt();
    Optional<SeasonFile> file = config.get().season(season);
    Position at;
    if (candidate.isPresent()) {
      Optional<Position> found =
          file.flatMap(f -> Optional.ofNullable(f.spawnCandidates().get(candidate.get())));
      if (found.isEmpty()) {
        source.reply(unknown(season, candidate.get()));
        return;
      }
      at = found.get();
    } else if (typed.isPresent()) {
      Optional<Position> read = typed(source, typed.get());
      if (read.isEmpty()) {
        return;
      }
      at = read.get();
    } else {
      at = platform.players().position(source.player().get());
    }
    Optional<Zone> holding = file.flatMap(f -> f.zoneHolding(at));
    if (holding.isPresent()) {
      source.reply(
          messages.get(
              "zone.spawn-inside", "season", Integer.toString(season), "zone", holding.get().id()));
      return;
    }
    if (config.get().inHall(at)) {
      // Launch would put everyone in the Hall (questions 256).
      source.reply(messages.get("spawn.in-hall", "season", Integer.toString(season)));
      return;
    }
    edits.edit(
        source,
        new ConfigEdits.Change(
            "seasons/" + season + ".yml spawn",
            file.flatMap(SeasonFile::spawn).map(ConfigEdits::describe).orElse(ConfigEdits.NONE),
            ConfigEdits.describe(at)),
        loader.seasonFile(season),
        () -> SeasonFiles.writeSpawn(loader.seasonFile(season), backups, season, at),
        "spawn.failed",
        () -> {
          source.reply(
              candidate.isPresent()
                  ? place("spawn.set-candidate", season, at, "name", candidate.get())
                  : place("spawn.set", season, at));
          warnIfFar(source, at);
          help.after(source, "season.spawn.set");
        });
  }

  // Candidates

  void add(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    String name = arguments.string("name");
    OptionalInt next = nextSeason();
    if (next.isEmpty()) {
      source.reply(messages.get("season.none"));
      return;
    }
    Optional<String> typed = arguments.optionalString("at");
    if (source.player().isEmpty() && typed.isEmpty()) {
      source.reply(messages.get("spawn.candidate.player-only"));
      return;
    }
    int season = next.getAsInt();
    // "at" and "here" read as positions in the commands that take a candidate (questions 265).
    if (!SeasonFile.CANDIDATE_NAME.matcher(name).matches()
        || name.equals("at")
        || name.equals(Positions.HERE)) {
      source.reply(messages.get("spawn.candidate.bad-name"));
      return;
    }
    Optional<SeasonFile> file = config.get().season(season);
    if (file.map(f -> f.spawnCandidates().containsKey(name)).orElse(false)) {
      source.reply(
          messages.get("spawn.candidate.exists", "season", Integer.toString(season), "name", name));
      return;
    }
    Optional<Position> found =
        typed.isPresent()
            ? typed(source, typed.get())
            : Optional.of(platform.players().position(source.player().get()));
    if (found.isEmpty()) {
      return;
    }
    Position at = found.get();
    if (!candidateFits(source, season, file, at)) {
      return;
    }
    edits.edit(
        source,
        new ConfigEdits.Change(
            "seasons/" + season + ".yml spawnCandidates." + name,
            ConfigEdits.NONE,
            ConfigEdits.describe(at)),
        loader.seasonFile(season),
        () -> SeasonFiles.writeCandidate(loader.seasonFile(season), backups, season, name, at),
        "spawn.candidate.failed",
        () -> {
          source.reply(place("spawn.candidate.added", season, at, "name", name));
          warnIfFar(source, at);
        });
  }

  /** {@code candidate move <name> [position]}: the same candidate somewhere else. */
  void move(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    String name = arguments.string("name");
    OptionalInt next = nextSeason();
    if (next.isEmpty()) {
      source.reply(messages.get("season.none"));
      return;
    }
    Optional<String> typed = arguments.optionalString("at");
    if (source.player().isEmpty() && typed.isEmpty()) {
      source.reply(messages.get("spawn.candidate.player-only"));
      return;
    }
    int season = next.getAsInt();
    Optional<SeasonFile> file = config.get().season(season);
    Optional<Position> before =
        file.flatMap(f -> Optional.ofNullable(f.spawnCandidates().get(name)));
    if (before.isEmpty()) {
      source.reply(unknown(season, name));
      return;
    }
    Optional<Position> found =
        typed.isPresent()
            ? typed(source, typed.get())
            : Optional.of(platform.players().position(source.player().get()));
    if (found.isEmpty()) {
      return;
    }
    Position at = found.get();
    if (!candidateFits(source, season, file, at)) {
      return;
    }
    edits.edit(
        source,
        new ConfigEdits.Change(
            "seasons/" + season + ".yml spawnCandidates." + name,
            ConfigEdits.describe(before.get()),
            ConfigEdits.describe(at)),
        loader.seasonFile(season),
        () -> SeasonFiles.writeCandidate(loader.seasonFile(season), backups, season, name, at),
        "spawn.candidate.failed",
        () -> {
          source.reply(place("spawn.candidate.moved", season, at, "name", name));
          warnIfFar(source, at);
        });
  }

  /** Whether a candidate may go there: outside every zone and the Hall; if not, says why. */
  private boolean candidateFits(
      CommandSource source, int season, Optional<SeasonFile> file, Position at) {
    Messages messages = messages();
    Optional<Zone> holding = file.flatMap(f -> f.zoneHolding(at));
    if (holding.isPresent()) {
      source.reply(
          messages.get(
              "spawn.candidate.inside",
              "season",
              Integer.toString(season),
              "zone",
              holding.get().id()));
      return false;
    }
    if (config.get().inHall(at)) {
      source.reply(messages.get("spawn.candidate.in-hall", "season", Integer.toString(season)));
      return false;
    }
    return true;
  }

  /** {@code spawn show [season]}: a season's spawn, the next season's by default. */
  void show(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    OptionalInt next = nextSeason();
    if (next.isEmpty()) {
      source.reply(messages.get("season.none"));
      return;
    }
    int season =
        arguments.values().containsKey("season") ? arguments.integer("season") : next.getAsInt();
    Optional<Position> spawn = config.get().season(season).flatMap(SeasonFile::spawn);
    if (spawn.isEmpty()) {
      source.reply(messages.get("spawn.show-none", "season", Integer.toString(season)));
      return;
    }
    source.reply(
        place(
            "spawn.show",
            season,
            spawn.get(),
            "distance",
            Long.toString(SeasonFile.distanceFromOrigin(spawn.get()))));
  }

  /** {@code spawn clear}: the next season has no spawn again, so launch waits for one. */
  void clear(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    OptionalInt next = nextSeason();
    if (next.isEmpty()) {
      source.reply(messages.get("season.none"));
      return;
    }
    int season = next.getAsInt();
    Optional<Position> spawn = config.get().season(season).flatMap(SeasonFile::spawn);
    if (spawn.isEmpty()) {
      source.reply(messages.get("spawn.show-none", "season", Integer.toString(season)));
      return;
    }
    edits.edit(
        source,
        new ConfigEdits.Change(
            "seasons/" + season + ".yml spawn",
            ConfigEdits.describe(spawn.get()),
            ConfigEdits.NONE),
        loader.seasonFile(season),
        () -> SeasonFiles.clearSpawn(loader.seasonFile(season), backups, season),
        "spawn.failed",
        () -> source.reply(messages().get("spawn.cleared", "season", Integer.toString(season))));
  }

  /** A position typed after a command, in the sender's dimension; empty after saying why not. */
  private Optional<Position> typed(CommandSource source, String text) {
    Optional<Position> origin = source.player().map(p -> platform.players().position(p));
    String dimension = origin.map(Position::dimension).orElse("minecraft:overworld");
    Positions.Read read = Positions.typed(text, origin, dimension, Positions.facing(origin));
    if (read instanceof Positions.Read.Block block) {
      return Optional.of(block.block());
    }
    source.reply(messages().get(((Positions.Read.Failed) read).messageKey()));
    return Optional.empty();
  }

  void remove(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    String name = arguments.string("name");
    OptionalInt next = nextSeason();
    if (next.isEmpty()) {
      source.reply(messages.get("season.none"));
      return;
    }
    int season = next.getAsInt();
    Optional<Position> at =
        config
            .get()
            .season(season)
            .flatMap(f -> Optional.ofNullable(f.spawnCandidates().get(name)));
    if (at.isEmpty()) {
      source.reply(unknown(season, name));
      return;
    }
    edits.edit(
        source,
        new ConfigEdits.Change(
            "seasons/" + season + ".yml spawnCandidates." + name,
            ConfigEdits.describe(at.get()),
            ConfigEdits.NONE),
        loader.seasonFile(season),
        () -> SeasonFiles.removeCandidate(loader.seasonFile(season), backups, season, name),
        "spawn.candidate.failed",
        () ->
            source.reply(
                messages()
                    .get(
                        "spawn.candidate.removed",
                        "season",
                        Integer.toString(season),
                        "name",
                        name)));
  }

  /** Each candidate with its distance from 0, 0, and which one the spawn is. */
  void list(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    OptionalInt next = nextSeason();
    if (next.isEmpty()) {
      source.reply(messages.get("season.none"));
      return;
    }
    int season = next.getAsInt();
    String number = Integer.toString(season);
    Optional<SeasonFile> file = config.get().season(season);
    Map<String, Position> candidates = file.map(SeasonFile::spawnCandidates).orElse(Map.of());
    if (candidates.isEmpty()) {
      source.reply(messages.get("spawn.candidate.none", "season", number));
      return;
    }
    source.reply(messages.get("spawn.candidate.header", "season", number));
    Optional<Position> spawn = file.flatMap(SeasonFile::spawn);
    for (Map.Entry<String, Position> candidate : candidates.entrySet()) {
      Position at = candidate.getValue();
      String notes =
          (spawn.isPresent() && sameBlock(spawn.get(), at)
                  ? messages.text("spawn.candidate.the-spawn")
                  : "")
              + (SeasonFile.beyondStrongholds(at) ? messages.text("spawn.candidate.beyond") : "");
      source.reply(
          messages.get(
              "spawn.candidate.line",
              "name",
              candidate.getKey(),
              "x",
              Integer.toString(at.blockX()),
              "y",
              Integer.toString(at.blockY()),
              "z",
              Integer.toString(at.blockZ()),
              "dimension",
              at.dimension(),
              "distance",
              Long.toString(SeasonFile.distanceFromOrigin(at)),
              "notes",
              notes));
    }
  }

  // Helpers

  /** Strongholds may be out of reach from a spawn this far out: a warning, never a refusal. */
  private void warnIfFar(CommandSource source, Position at) {
    if (SeasonFile.beyondStrongholds(at)) {
      source.reply(
          messages()
              .get("spawn.far", "distance", Long.toString(SeasonFile.distanceFromOrigin(at))));
    }
  }

  /** The season these commands edit: the next one, which is being prepared. */
  private OptionalInt nextSeason() {
    return seasons.state().map(s -> OptionalInt.of(s.season() + 1)).orElse(OptionalInt.empty());
  }

  private Message unknown(int season, String name) {
    return messages()
        .get("spawn.candidate.unknown", "season", Integer.toString(season), "name", name);
  }

  /** A message about a position in a season, with any further placeholders. */
  private Message place(String key, int season, Position at, String... more) {
    String[] placeholders = new String[10 + more.length];
    String[] common = {
      "season",
      Integer.toString(season),
      "x",
      Integer.toString(at.blockX()),
      "y",
      Integer.toString(at.blockY()),
      "z",
      Integer.toString(at.blockZ()),
      "dimension",
      at.dimension()
    };
    System.arraycopy(common, 0, placeholders, 0, common.length);
    System.arraycopy(more, 0, placeholders, common.length, more.length);
    return messages().get(key, placeholders);
  }

  private static boolean sameBlock(Position a, Position b) {
    return a.dimension().equals(b.dimension())
        && a.blockX() == b.blockX()
        && a.blockY() == b.blockY()
        && a.blockZ() == b.blockZ();
  }

  private Messages messages() {
    return config.get().messages();
  }
}
