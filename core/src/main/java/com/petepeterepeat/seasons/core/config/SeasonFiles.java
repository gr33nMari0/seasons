// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.petepeterepeat.seasons.core.store.AtomicFiles;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
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
