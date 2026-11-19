// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static com.petepeterepeat.seasons.core.Seasons.PERMISSION_ADMIN_SEASON;

import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.config.ConfigEdits;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.config.ConfigLoader;
import com.petepeterepeat.seasons.core.config.SeasonFile;
import com.petepeterepeat.seasons.core.config.SeasonFiles;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.season.SeasonService;
import com.petepeterepeat.seasons.core.zone.Zone;
import java.nio.file.Path;
import java.util.Optional;

/**
 * {@code /season spawn}: the next season's spawn. Edits go to the next season's file through {@link
 * ConfigEdits}, so they are in force at once and audited.
 */
final class SpawnCommands {
  private final Platform platform;
  private final ConfigHolder config;
  private final ConfigLoader loader;
  private final ConfigEdits edits;
  private final SeasonService seasons;
  private final Path backups;

  /**
   * @param backups {@code <world>/seasons/backups/seasons}
   */
  SpawnCommands(
      Platform platform,
      ConfigHolder config,
      ConfigLoader loader,
      ConfigEdits edits,
      SeasonService seasons,
      Path backups) {
    this.platform = platform;
    this.config = config;
    this.loader = loader;
    this.edits = edits;
    this.seasons = seasons;
    this.backups = backups;
  }

  /** The {@code spawn} node under {@code /season}. */
  CommandNode node() {
    return CommandNode.literal("spawn")
        .permission(PERMISSION_ADMIN_SEASON)
        .then(CommandNode.literal("set").executes(this::set))
        .build();
  }

  void set(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    if (source.player().isEmpty()) {
      source.reply(messages.get("spawn.player-only"));
      return;
    }
    if (seasons.state().isEmpty()) {
      source.reply(messages.get("season.none"));
      return;
    }
    Position at = platform.players().position(source.player().get());
    int next = seasons.state().get().season() + 1;
    Optional<SeasonFile> file = config.get().season(next);
    Optional<Zone> holding = file.flatMap(f -> f.zoneHolding(at));
    if (holding.isPresent()) {
      source.reply(
          messages.get(
              "zone.spawn-inside", "season", Integer.toString(next), "zone", holding.get().id()));
      return;
    }
    edits.edit(
        source,
        new ConfigEdits.Change(
            "seasons/" + next + ".yml spawn",
            file.flatMap(SeasonFile::spawn).map(SpawnCommands::describe).orElse(ConfigEdits.NONE),
            describe(at)),
        () -> SeasonFiles.writeSpawn(loader.seasonFile(next), backups, next, at),
        "spawn.failed",
        () ->
            source.reply(
                messages()
                    .get(
                        "spawn.set",
                        "season",
                        Integer.toString(next),
                        "x",
                        Integer.toString(at.blockX()),
                        "y",
                        Integer.toString(at.blockY()),
                        "z",
                        Integer.toString(at.blockZ()),
                        "dimension",
                        at.dimension())));
  }

  /** A spawn as the audit log writes it: {@code 3000, 64, 0 in minecraft:overworld}. */
  static String describe(Position at) {
    return at.blockX() + ", " + at.blockY() + ", " + at.blockZ() + " in " + at.dimension();
  }

  private Messages messages() {
    return config.get().messages();
  }
}
