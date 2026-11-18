// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.testkit;

import com.petepeterepeat.seasons.api.SeasonsApi;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.paper.PaperItemCodec;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Runs the scenario suite once the server has started, then stops the server.
 *
 * <p>It checks Seasons is enabled, writes item samples through the Paper adapter's item codec for
 * the NBT neutrality test, launches the Mineflayer runner for one session of one scenario, and
 * writes {@code result.json} to the output directory. Gradle reads that file to pass or fail the
 * build. Settings come from system properties set by the Gradle task.
 */
public final class ScenarioPlugin extends JavaPlugin implements Listener {
  private static final Duration RUNNER_TIMEOUT = Duration.ofMinutes(10);

  private final Path out = Path.of(property("seasons.scenario.out"));
  private final Path nbt = Path.of(property("seasons.scenario.nbt"));
  private final String adminName = property("seasons.scenario.admin");

  /**
   * When this plugin was loaded and enabled, for the timing report: before and after the worlds.
   */
  private long loadedAt;

  private long enabledAt;

  @Override
  public void onLoad() {
    loadedAt = System.currentTimeMillis();
  }

  @Override
  public void onEnable() {
    enabledAt = System.currentTimeMillis();
    getServer().getPluginManager().registerEvents(this, this);
    ScenarioCommands commands = new ScenarioCommands(this);
    getServer().getPluginManager().registerEvents(commands, this);
    getLifecycleManager()
        .registerEventHandler(
            LifecycleEvents.COMMANDS,
            event ->
                event.registrar().register("scenario", "Scenario hooks (docs/adr/0003)", commands));
    commands
        .hooks()
        .ifPresent(hooks ->
            hooks.beforeHalt(() -> {
              copyArchives();
              try {
                Files.createDirectories(out);
                Files.writeString(out.resolve("halted.json"), "{\"halted\":true}");
              } catch (IOException e) {
                getSLF4JLogger().error("Can't write halted.json", e);
              }
            }));
  }

  /** The admin bot runs admin commands, so it is made an op as it joins. */
  @EventHandler
  void onJoin(PlayerJoinEvent event) {
    if (event.getPlayer().getName().equals(adminName)) {
      event.getPlayer().setOp(true);
    }
  }

  @EventHandler
  void onLoad(ServerLoadEvent event) {
    if (event.getType() != ServerLoadEvent.LoadType.STARTUP) {
      return;
    }
    writeBootTiming();
    Map<String, Object> result = new LinkedHashMap<>();
    List<String> failures = new ArrayList<>();
    checkSeasonsEnabled(failures);
    writeItemSamples(failures);
    result.put("preconditions", failures);
    if (!failures.isEmpty()) {
      finish(result, false);
      return;
    }
    Thread.ofVirtual()
        .name("scenario-runner")
        .start(() -> {
          int exit = runScenarios();
          result.put("runnerExit", exit);
          getServer().getGlobalRegionScheduler().execute(this, () -> finish(result, exit == 0));
        });
  }

  /**
   * Writes when the JVM started, this plugin loaded and enabled, and the server finished loading,
   * as epoch milliseconds, for Gradle's scenario timing report.
   */
  private void writeBootTiming() {
    String json =
        "{\"jvmStart\":%d,\"pluginLoad\":%d,\"pluginEnable\":%d,\"serverLoaded\":%d}"
            .formatted(
                ManagementFactory.getRuntimeMXBean().getStartTime(),
                loadedAt,
                enabledAt,
                System.currentTimeMillis());
    try {
      Files.createDirectories(out);
      Files.writeString(out.resolve("boot-timing.json"), json);
    } catch (IOException e) {
      getSLF4JLogger().error("Can't write boot-timing.json", e);
    }
  }

  private void checkSeasonsEnabled(List<String> failures) {
    for (String plugin : List.of("Seasons", "SeasonsWeb")) {
      if (!getServer().getPluginManager().isPluginEnabled(plugin)) {
        failures.add(plugin + " is not enabled");
      }
    }
    if (getServer().getServicesManager().getRegistration(SeasonsApi.class) == null) {
      failures.add("Seasons API is not registered");
    }
  }

  /**
   * Writes items through the adapter's codec, as archives will store them, and checks each one
   * decodes back to an equal item. The NBT neutrality test then opens the files with a plain NBT
   * reader.
   */
  private void writeItemSamples(List<String> failures) {
    PaperItemCodec codec = new PaperItemCodec();
    Path dir = nbt;
    try {
      Files.createDirectories(dir);
      for (Map.Entry<String, ItemStack> sample : itemSamples().entrySet()) {
        NeutralItem item = codec.encode(sample.getValue()).orElseThrow();
        Files.write(dir.resolve(sample.getKey() + ".dat"), item.nbt());
        if (!codec.decode(item).equals(sample.getValue())) {
          failures.add(sample.getKey() + " does not survive an encode and decode");
        }
      }
    } catch (IOException e) {
      failures.add("Can't write item samples: " + e);
    }
  }

  private static Map<String, ItemStack> itemSamples() {
    Map<String, ItemStack> samples = new LinkedHashMap<>();
    samples.put("dirt", ItemStack.of(Material.DIRT, 64));

    ItemStack sword = ItemStack.of(Material.NETHERITE_SWORD);
    sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
    sword.editMeta(meta -> meta.customName(Component.text("Season 2 sword")));
    samples.put("named-enchanted-sword", sword);

    ItemStack shulker = ItemStack.of(Material.SHULKER_BOX);
    shulker.editMeta(
        BlockStateMeta.class,
        meta -> {
          ShulkerBox box = (ShulkerBox) meta.getBlockState();
          box.getInventory().addItem(ItemStack.of(Material.DIAMOND, 32), sword.clone());
          meta.setBlockState(box);
        });
    samples.put("shulker-with-contents", shulker);
    return samples;
  }

  private int runScenarios() {
    List<String> command =
        List.of(
            property("seasons.scenario.node"),
            property("seasons.scenario.runner"),
            "--host",
            "127.0.0.1",
            "--port",
            String.valueOf(getServer().getPort()),
            "--version",
            property("seasons.scenario.clientVersion"),
            "--admin",
            adminName,
            "--out",
            out.toString(),
            "--server-dir",
            Path.of("").toAbsolutePath().toString(),
            "--tls",
            property("seasons.scenario.tls"),
            "--session",
            property("seasons.scenario.session"),
            property("seasons.scenario.name"));
    try {
      Process process = new ProcessBuilder(command).inheritIO().start();
      if (!process.waitFor(RUNNER_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
        getSLF4JLogger().error("Scenario runner timed out after {}", RUNNER_TIMEOUT);
        process.destroyForcibly();
        return -1;
      }
      return process.exitValue();
    } catch (IOException e) {
      getSLF4JLogger().error("Can't start the scenario runner", e);
      return -1;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return -1;
    }
  }

  /**
   * Copies the archives and vault pages Seasons wrote beside the item samples, for the NBT
   * neutrality test (A10).
   */
  private void copyArchives() {
    // Where the Paper adapter keeps Seasons' files: the overworld's folder.
    Path data =
        getServer().getWorlds().getFirst().getWorldFolder().toPath().resolve("seasons/data");
    Path into = nbt.resolve("archives");
    String prefix =
        property("seasons.scenario.name") + "-" + property("seasons.scenario.session") + "-";
    for (String kind : List.of("archives", "vaults")) {
      Path dir = data.resolve(kind);
      if (!Files.isDirectory(dir)) {
        continue;
      }
      try (var files = Files.walk(dir)) {
        Files.createDirectories(into);
        for (Path file : files.filter(f -> f.toString().endsWith(".dat")).toList()) {
          String name = prefix + kind + "-" + dir.relativize(file).toString().replace('/', '-');
          Files.copy(file, into.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
      } catch (IOException e) {
        getSLF4JLogger().error("Can't copy {} for the NBT neutrality test", dir, e);
      }
    }
  }

  private void finish(Map<String, Object> result, boolean passed) {
    copyArchives();
    result.put("passed", passed);
    try {
      Files.createDirectories(out);
      Files.writeString(out.resolve("result.json"), toJson(result));
    } catch (IOException e) {
      getSLF4JLogger().error("Can't write the scenario result", e);
    }
    getSLF4JLogger().info("Scenarios {}; stopping the server", passed ? "passed" : "FAILED");
    getServer().shutdown();
  }

  /** Enough JSON for a flat map of booleans, numbers and string lists. */
  private static String toJson(Map<String, Object> map) {
    StringBuilder json = new StringBuilder("{");
    map.forEach((key, value) -> {
      if (json.length() > 1) {
        json.append(',');
      }
      json.append(quote(key)).append(':');
      if (value instanceof List<?> list) {
        json.append('[');
        for (int i = 0; i < list.size(); i++) {
          json.append(i == 0 ? "" : ",").append(quote(String.valueOf(list.get(i))));
        }
        json.append(']');
      } else if (value instanceof String s) {
        json.append(quote(s));
      } else {
        json.append(value);
      }
    });
    return json.append('}').toString();
  }

  private static String quote(String s) {
    return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
  }

  private static String property(String name) {
    String value = System.getProperty(name);
    if (value == null) {
      throw new IllegalStateException("System property " + name + " is not set");
    }
    return value;
  }
}
