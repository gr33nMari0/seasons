// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Statistics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.Statistic;

final class PaperStatistics implements Statistics {
  private final PaperPlayers players;
  private final Path worldFolder;

  /**
   * @param worldFolder the main world's folder, which holds the game's stats files
   */
  PaperStatistics(PaperPlayers players, Path worldFolder) {
    this.players = players;
    this.worldFolder = worldFolder;
  }

  /**
   * The game's own stats file: its General tab ({@code minecraft:custom}) as last saved. Since 26.1
   * the files sit in {@code players/stats}; before then in {@code stats}.
   */
  @Override
  public Optional<Map<String, Long>> stored(PlayerId id) {
    for (Path file :
        List.of(
            worldFolder.resolve("players/stats/" + id.uuid() + ".json"),
            worldFolder.resolve("stats/" + id.uuid() + ".json"))) {
      if (!Files.isRegularFile(file)) {
        continue;
      }
      try {
        JsonObject custom =
            JsonParser.parseString(Files.readString(file))
                .getAsJsonObject()
                .getAsJsonObject("stats")
                .getAsJsonObject("minecraft:custom");
        Map<String, Long> values = new HashMap<>();
        if (custom != null) {
          for (Map.Entry<String, JsonElement> entry : custom.entrySet()) {
            values.put(entry.getKey(), entry.getValue().getAsLong());
          }
        }
        return Optional.of(values);
      } catch (IOException | RuntimeException e) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  /**
   * Bukkit still names some statistics by their pre-1.13 names. The game, its stats files and the
   * client's translations use these, so the core sees the game's ids.
   */
  private static final Map<String, String> GAME_IDS =
      Map.ofEntries(
          Map.entry("minecraft:play_one_minute", "minecraft:play_time"),
          Map.entry("minecraft:drop_count", "minecraft:drop"),
          Map.entry("minecraft:armor_cleaned", "minecraft:clean_armor"),
          Map.entry("minecraft:banner_cleaned", "minecraft:clean_banner"),
          Map.entry("minecraft:cake_slices_eaten", "minecraft:eat_cake_slice"),
          Map.entry("minecraft:cauldron_filled", "minecraft:fill_cauldron"),
          Map.entry("minecraft:cauldron_used", "minecraft:use_cauldron"),
          Map.entry("minecraft:chest_opened", "minecraft:open_chest"),
          Map.entry("minecraft:dispenser_inspected", "minecraft:inspect_dispenser"),
          Map.entry("minecraft:dropper_inspected", "minecraft:inspect_dropper"),
          Map.entry("minecraft:hopper_inspected", "minecraft:inspect_hopper"),
          Map.entry("minecraft:enderchest_opened", "minecraft:open_enderchest"),
          Map.entry("minecraft:shulker_box_opened", "minecraft:open_shulker_box"),
          Map.entry("minecraft:flower_potted", "minecraft:pot_flower"),
          Map.entry("minecraft:item_enchanted", "minecraft:enchant_item"),
          Map.entry("minecraft:noteblock_played", "minecraft:play_noteblock"),
          Map.entry("minecraft:noteblock_tuned", "minecraft:tune_noteblock"),
          Map.entry("minecraft:record_played", "minecraft:play_record"),
          Map.entry("minecraft:trapped_chest_triggered", "minecraft:trigger_trapped_chest"),
          Map.entry("minecraft:beacon_interaction", "minecraft:interact_with_beacon"),
          Map.entry("minecraft:brewingstand_interaction", "minecraft:interact_with_brewingstand"),
          Map.entry(
              "minecraft:crafting_table_interaction", "minecraft:interact_with_crafting_table"),
          Map.entry("minecraft:furnace_interaction", "minecraft:interact_with_furnace"));

  /** The General tab holds the untyped statistics, by the game's own ids. */
  @Override
  public Map<String, Long> general(PlayerId id) {
    Map<String, Long> values = new HashMap<>();
    players
        .player(id)
        .ifPresent(player -> {
          for (Statistic statistic : Statistic.values()) {
            if (statistic.getType() == Statistic.Type.UNTYPED) {
              String key = statistic.getKey().asString();
              values.put(GAME_IDS.getOrDefault(key, key), (long) player.getStatistic(statistic));
            }
          }
        });
    return values;
  }

  /** Block counts from the typed statistics, summed over every block. */
  @Override
  public Map<String, Long> counts(PlayerId id) {
    Map<String, Long> values = new HashMap<>();
    players
        .player(id)
        .ifPresent(player -> {
          long placed = 0;
          long mined = 0;
          for (Material material : Material.values()) {
            if (material.name().startsWith("LEGACY_") || !material.isBlock()) {
              continue;
            }
            try {
              mined += player.getStatistic(Statistic.MINE_BLOCK, material);
              if (material.isItem()) {
                placed += player.getStatistic(Statistic.USE_ITEM, material);
              }
            } catch (IllegalArgumentException e) {
              // A block the statistic doesn't track, such as air.
            }
          }
          values.put(
              Statistics.DIAMONDS_MINED,
              (long) player.getStatistic(Statistic.MINE_BLOCK, Material.DIAMOND_ORE)
                  + player.getStatistic(Statistic.MINE_BLOCK, Material.DEEPSLATE_DIAMOND_ORE));
          values.put(
              Statistics.DEBRIS_MINED,
              (long) player.getStatistic(Statistic.MINE_BLOCK, Material.ANCIENT_DEBRIS));
          values.put(Statistics.BLOCKS_PLACED, placed);
          values.put(Statistics.BLOCKS_MINED, mined);
        });
    return values;
  }
}
