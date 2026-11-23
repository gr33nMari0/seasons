// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.testkit;

import com.petepeterepeat.seasons.api.SeasonsApi;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.scenario.ScenarioHooks;
import com.petepeterepeat.seasons.paper.PaperItemCodec;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * Runs scenarios once the server has started, then stops the server.
 *
 * <p>It checks Seasons is enabled, writes item samples through the Paper adapter's item codec for
 * the NBT neutrality test, launches the Mineflayer runner for the scenario sessions this boot runs,
 * and writes {@code result.json} to the output directory. On a shared server the runner asks,
 * between scenarios, for the server to be reset to a fresh boot ({@link ScenarioWorld}) over its
 * standard output, and reads the answer from its standard input. Gradle reads the runner's results
 * to pass or fail the build. Settings come from system properties set by Gradle.
 */
public final class ScenarioPlugin extends JavaPlugin implements Listener {
  private static final Duration RUNNER_TIMEOUT = Duration.ofMinutes(30);

  /** How the runner's requests start on its standard output. */
  private static final String CONTROL = "@@scenario ";

  private final Path out = Path.of(property("seasons.scenario.out"));
  private final Path nbt = Path.of(property("seasons.scenario.nbt"));
  private final String adminName = property("seasons.scenario.admin");
  private final boolean shared = Boolean.getBoolean("seasons.scenario.shared");

  /** The scenario session running, as {@code name-session}, for naming archive copies. */
  private volatile String step = "boot";

  private @org.jspecify.annotations.Nullable ScenarioWorld world;

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
    // Every scenario server can reset; only a shared one does so between scenarios by itself.
    ScenarioWorld resettable = new ScenarioWorld(this, Path.of(property("seasons.scenario.state")));
    getServer().getPluginManager().registerEvents(resettable, this);
    world = resettable;
    ScenarioPerf perf = new ScenarioPerf(this);
    getServer().getPluginManager().registerEvents(perf, this);
    ScenarioCommands commands = new ScenarioCommands(this, this::reset, perf);
    getServer().getPluginManager().registerEvents(commands, this);
    getLifecycleManager()
        .registerEventHandler(
            LifecycleEvents.COMMANDS,
            event ->
                event.registrar().register("scenario", "Scenario hooks (docs/adr/0003)", commands));
    // A save point writes every world to disk at once, as the vanilla command does.
    commands
        .hooks()
        .ifPresent(hooks ->
            hooks.onSave(() ->
                getServer().dispatchCommand(getServer().getConsoleSender(), "save-all flush")));
    commands
        .hooks()
        .ifPresent(hooks ->
            hooks.beforeHalt(() -> {
              copyArchives(step);
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
    ScenarioWorld resettable = world;
    if (resettable != null && Boolean.getBoolean("seasons.scenario.fresh")) {
      try {
        resettable.freshBoot();
      } catch (RuntimeException e) {
        getSLF4JLogger().error("Can't record the fresh boot", e);
        failures.add("Can't record the fresh boot: " + e);
      }
    }
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
        new ArrayList<>(
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
                "--webhook-port",
                property("seasons.scenario.webhookPort"),
                "--web-port",
                property("seasons.scenario.webPort")));
    if (shared) {
      command.add("--shared");
    }
    command.addAll(List.of(property("seasons.scenario.steps").split(",", -1)));
    // The server's own standard output, past its logger, so runner lines read as they did.
    PrintStream console = new PrintStream(new FileOutputStream(FileDescriptor.out), true);
    try {
      Process process =
          new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
      Writer replies = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
      try (BufferedReader lines =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = lines.readLine()) != null) {
          if (line.startsWith(CONTROL)) {
            control(line.substring(CONTROL.length()), replies);
          } else {
            console.println(line);
          }
        }
      }
      if (!process.waitFor(RUNNER_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
        getSLF4JLogger().error("Scenario runner timed out after {}", RUNNER_TIMEOUT);
        process.destroyForcibly();
        return -1;
      }
      return process.exitValue();
    } catch (IOException e) {
      getSLF4JLogger().error("Can't run the scenario runner", e);
      return -1;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return -1;
    }
  }

  /**
   * A request from the runner: {@code step <name> <session>} says which session is starting, and
   * {@code reset} resets the server to a fresh boot. Every request gets one line back: {@code ok},
   * or {@code leak} and what the reset left behind, or {@code failed} and why.
   */
  private void control(String request, Writer replies) throws IOException {
    String[] words = request.trim().split(" ", -1);
    String reply;
    switch (words[0]) {
      case "step" -> {
        step = words[1] + "-" + words[2];
        reply = "ok";
      }
      case "reset" -> {
        CompletableFuture<List<String>> done = new CompletableFuture<>();
        getServer()
            .getGlobalRegionScheduler()
            .execute(
                this,
                () -> {
                  var _ =
                      reset()
                          .whenComplete((leaks, failure) -> {
                            if (failure != null) {
                              done.completeExceptionally(failure);
                            } else {
                              done.complete(leaks);
                            }
                          });
                });
        try {
          List<String> leaks = done.get(2, TimeUnit.MINUTES);
          reply = leaks.isEmpty() ? "ok" : "leak " + String.join(" | ", leaks);
        } catch (ExecutionException | TimeoutException e) {
          getSLF4JLogger().error("Scenario reset failed", e);
          reply = "failed " + e.getMessage();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          reply = "failed interrupted";
        }
      }
      default -> reply = "failed unknown request " + words[0];
    }
    replies.write(reply.replace('\n', ' ') + "\n");
    replies.flush();
  }

  /**
   * Resets the server to a fresh boot (see {@link ScenarioWorld}), keeping copies of the archives
   * Seasons wrote first. Runs on the server thread; completes with the leaks found.
   */
  CompletableFuture<List<String>> reset() {
    ScenarioWorld resettable = world;
    ScenarioHooks hooks = getServer().getServicesManager().load(ScenarioHooks.class);
    if (resettable == null || hooks == null) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("Seasons' scenario hooks aren't running"));
    }
    String archived = step;
    // Seasons Web back to a fresh install too: web.yml, sessions, codes and limits.
    com.petepeterepeat.seasons.web.WebScenarioHooks web =
        getServer()
            .getServicesManager()
            .load(com.petepeterepeat.seasons.web.WebScenarioHooks.class);
    if (web != null) {
      web.reset();
    }
    return resettable.reset(hooks, () -> copyArchives(archived));
  }

  /**
   * Copies the archives and vault pages Seasons wrote beside the item samples, for the NBT
   * neutrality test (A10).
   */
  private void copyArchives(String prefixStep) {
    // Where the Paper adapter keeps Seasons' files: the overworld's folder.
    Path data =
        getServer().getWorlds().getFirst().getWorldFolder().toPath().resolve("seasons/data");
    Path into = nbt.resolve("archives");
    String prefix = prefixStep + "-";
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
    copyArchives(step);
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
