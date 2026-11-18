// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.store.AtomicFiles;
import com.petepeterepeat.seasons.core.zone.Zone;
import com.petepeterepeat.seasons.core.zone.ZoneFiles;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Command edits to season files. Each edit changes only its own keys and keeps the rest of the file
 * (spawn, zones and anything else) as it was, with a backup of the previous copy.
 */
public final class SeasonFiles {
  private SeasonFiles() {}

  /** Creates the season file if it doesn't exist yet. */
  public static void create(Path file, Path backupDir, int season) throws IOException {
    if (Files.notExists(file)) {
      edit(file, backupDir, season, node -> {});
    }
  }

  /** Sets or clears {@code schedule.plannedEnd}. */
  public static void writePlannedEnd(
      Path file, Path backupDir, int season, Optional<ZonedDateTime> plannedEnd)
      throws IOException {
    edit(
        file,
        backupDir,
        season,
        node ->
            node.node("schedule", "plannedEnd")
                .set(plannedEnd.map(ZonedTimes::format).orElse(null)));
  }

  /**
   * Sets {@code spawn}. Whole-block positions are written as the block's centre, which is where a
   * player stands.
   */
  public static void writeSpawn(Path file, Path backupDir, int season, Position spawn)
      throws IOException {
    edit(
        file,
        backupDir,
        season,
        node -> {
          ConfigurationNode at = node.node("spawn");
          at.node("dimension").set(spawn.dimension());
          at.node("pos")
              .setList(Integer.class, List.of(spawn.blockX(), spawn.blockY(), spawn.blockZ()));
          at.node("yaw").set(Math.round(spawn.yaw()));
        });
  }

  /** Adds a zone to the file, or replaces the one with its id. */
  public static void writeZone(Path file, Path backupDir, int season, Zone zone)
      throws IOException {
    edit(file, backupDir, season, node -> ZoneFiles.write(node.node("zones"), zone));
  }

  /** Removes the zone with an id from the file. */
  public static void removeZone(Path file, Path backupDir, int season, String id)
      throws IOException {
    edit(file, backupDir, season, node -> node.node("zones").removeChild(id));
  }

  private interface Edit {
    void apply(ConfigurationNode node) throws IOException;
  }

  private static void edit(Path file, Path backupDir, int season, Edit edit) throws IOException {
    ConfigurationNode node =
        Files.exists(file)
            ? YamlConfigurationLoader.builder().path(file).build().load()
            : YamlConfigurationLoader.builder().build().createNode();
    node.node("schema-version").set(ConfigLoader.SCHEMA_VERSION);
    node.node("season").set(season);
    edit.apply(node);
    StringWriter out = new StringWriter();
    YamlConfigurationLoader.builder()
        .nodeStyle(NodeStyle.BLOCK)
        .sink(() -> new BufferedWriter(out))
        .build()
        .save(node);
    AtomicFiles.writeKeepingBackups(file, out.toString().getBytes(UTF_8), backupDir);
  }
}
