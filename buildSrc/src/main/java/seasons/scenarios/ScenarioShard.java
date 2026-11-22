// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package seasons.scenarios;

import groovy.json.JsonOutput;
import groovy.json.JsonSlurper;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.workers.WorkAction;

/**
 * Runs one shard of the scenario suite: boots its Paper server, which runs scenario sessions one
 * after another (resetting itself between them when shared), and boots it again whenever a session
 * needs a restart, a crash point halted it, a reset leaked or a scenario is isolated.
 *
 * <p>A boot gets a run of steps: on a shared server, every step up to the first session that isn't
 * its scenario's last (the server restarts after it, on the same world) or the first isolated
 * scenario (which gets a fresh world of its own). A fresh boot starts from an empty world and no
 * Seasons files. Each boot writes {@code boot-<n>/}; the shard writes {@code shard.json}, with
 * every step's result and every boot's timings, and {@code server.log}.
 */
public abstract class ScenarioShard implements WorkAction<ShardParameters> {
  private static final Logger LOG = Logging.getLogger(ScenarioShard.class);
  private static final Duration BOOT_ALLOWANCE = Duration.ofMinutes(2);
  private static final Duration PER_STEP = Duration.ofMinutes(3);
  private static final Duration HALT_RESULTS = Duration.ofSeconds(60);

  /**
   * @param plugins the optional plugins its server gets as well ({@code // @plugins}); a scenario
   *     with any is isolated
   */
  private record Step(
      String name, int session, int sessions, boolean isolated, List<String> plugins) {
    static Step parse(String text) {
      String[] parts = text.split(":", -1);
      List<String> plugins =
          parts.length > 4 && !parts[4].isEmpty() ? List.of(parts[4].split("\\+")) : List.of();
      return new Step(
          parts[0],
          Integer.parseInt(parts[1]),
          Integer.parseInt(parts[2]),
          parts[3].equals("1"),
          plugins);
    }

    String label() {
      return name + ":" + session;
    }

    boolean last() {
      return session == sessions;
    }
  }

  @Override
  public void execute() {
    ShardParameters p = getParameters();
    int shard = p.getShard().get();
    boolean shared = p.getShared().get();
    Path run = p.getRunDirectory().get().getAsFile().toPath();
    Path out = p.getOutputDirectory().get().getAsFile().toPath();
    Path state = run.resolve("scenario-state");
    List<Step> remaining = new ArrayList<>(p.getSteps().get().stream().map(Step::parse).toList());
    List<Map<String, Object>> results = new ArrayList<>();
    List<Map<String, Object>> boots = new ArrayList<>();
    deleteTree(out);
    mkdirs(out);
    boolean wipe = true;
    int boot = 0;
    while (!remaining.isEmpty()) {
      List<Step> segment = segment(remaining, shared);
      Step first = segment.getFirst();
      boolean fresh = first.session() == 1 && (wipe || !shared || first.isolated());
      if (fresh) {
        wipeWorld(run, state);
      }
      boot++;
      Path bootOut = out.resolve("boot-" + boot);
      Map<String, Object> timing = new LinkedHashMap<>();
      timing.put("boot", boot);
      timing.put("fresh", fresh);
      timing.put("steps", segment.size());
      timing.put("start", System.currentTimeMillis());
      runServer(p, shard, run, state, bootOut, out.resolve("nbt"), segment, shared && !first.isolated(), fresh, out.resolve("server.log"));
      timing.put("end", System.currentTimeMillis());
      Map<String, Object> runner = readJson(bootOut.resolve("results.json"));
      Map<String, Object> bootTiming = readJson(bootOut.resolve("boot-timing.json"));
      if (bootTiming != null) {
        timing.putAll(bootTiming);
      }
      if (runner != null) {
        timing.put("runnerStart", runner.get("startedAt"));
        timing.put("runnerEnd", runner.get("finishedAt"));
      }
      boots.add(timing);

      List<Map<String, Object>> ran = runnerResults(runner);
      wipe = !shared || first.isolated();
      for (Map<String, Object> result : ran) {
        Step step = remaining.removeFirst();
        result.put("shard", shard);
        result.put("boot", boot);
        results.add(result);
        report(shard, step, result, out);
        if (!Boolean.TRUE.equals(result.get("passed"))) {
          // Its later sessions can't run, and the world is in no known state.
          wipe = true;
          remaining.removeIf(s -> s.name().equals(step.name()));
        }
        if (Boolean.TRUE.equals(result.get("leaked"))) {
          wipe = true;
        }
      }
      if (ran.size() < segment.size()) {
        // A reset leaked, or the server stopped early. If a step had started without finishing,
        // or the boot ran nothing at all, that step failed and its later sessions are skipped.
        wipe = true;
        boolean started = runner != null && runner.get("current") != null;
        if (!remaining.isEmpty() && (started || ran.isEmpty())) {
          Step broken = remaining.removeFirst();
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("name", broken.name());
          result.put("session", broken.session());
          result.put("passed", false);
          result.put(
              "error",
              started
                  ? "the server stopped during it"
                  : "the server stopped before running it");
          result.put("ms", 0);
          result.put("shard", shard);
          result.put("boot", boot);
          results.add(result);
          report(shard, broken, result, out);
          remaining.removeIf(s -> s.name().equals(broken.name()));
        }
      }
      writeJson(out.resolve("shard.json"), Map.of("shard", shard, "results", results, "boots", boots));
    }
    writeJson(out.resolve("shard.json"), Map.of("shard", shard, "results", results, "boots", boots));
  }

  /** The steps the next boot runs. */
  private static List<Step> segment(List<Step> remaining, boolean shared) {
    List<Step> segment = new ArrayList<>();
    for (Step step : remaining) {
      if (!segment.isEmpty() && (!shared || step.isolated() || segment.getFirst().isolated())) {
        if (step.session() == 1) {
          break;
        }
      }
      segment.add(step);
      if (!step.last()) {
        break;
      }
      if (!shared || step.isolated()) {
        break;
      }
    }
    return segment;
  }

  private static void report(int shard, Step step, Map<String, Object> result, Path out) {
    double seconds = ((Number) result.getOrDefault("ms", 0)).doubleValue() / 1000;
    if (Boolean.TRUE.equals(result.get("passed"))) {
      LOG.lifecycle(String.format("[shard %d] PASS %s session %d (%.1f s)", shard, step.name(), step.session(), seconds));
    } else {
      String error = String.valueOf(result.get("error"));
      int cut = error.indexOf('\n');
      LOG.lifecycle(String.format(
          "[shard %d] FAIL %s session %d (%.1f s): %s%n         server log: %s",
          shard, step.name(), step.session(), seconds, cut < 0 ? error : error.substring(0, cut),
          out.resolve("server.log")));
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> runnerResults(Map<String, Object> runner) {
    if (runner == null || !(runner.get("results") instanceof List<?> list)) {
      return List.of();
    }
    List<Map<String, Object>> results = new ArrayList<>();
    for (Object item : list) {
      results.add(new LinkedHashMap<>((Map<String, Object>) item));
    }
    return results;
  }

  private void runServer(
      ShardParameters p,
      int shard,
      Path run,
      Path state,
      Path bootOut,
      Path nbt,
      List<Step> segment,
      boolean shared,
      boolean fresh,
      Path log) {
    mkdirs(bootOut);
    mkdirs(state);
    writeServerProperties(run, p.getServerPort().get());
    turnOffBStats(run);
    List<String> command = new ArrayList<>();
    command.add(p.getJava().get());
    command.addAll(p.getJvmArgs().get());
    command.add("-Dseasons.scenario.out=" + bootOut);
    command.add("-Dseasons.scenario.nbt=" + nbt);
    command.add("-Dseasons.scenario.state=" + state);
    command.add("-Dseasons.scenario.shared=" + shared);
    command.add("-Dseasons.scenario.fresh=" + fresh);
    command.add("-Dseasons.scenario.webhookPort=" + p.getWebhookPort().get());
    command.add(
        "-Dseasons.scenario.steps="
            + String.join(",", segment.stream().map(Step::label).toList()));
    command.add("-jar");
    command.add(p.getPaperJar().get().getAsFile().getAbsolutePath());
    command.add("--nogui");
    for (File plugin : p.getPluginJars().getFiles()) {
      command.add("-add-plugin=" + plugin.getAbsolutePath());
    }
    List<String> optional = segment.getFirst().plugins();
    for (String name : optional) {
      String jar = p.getOptionalPlugins().get().get(name);
      if (jar == null) {
        throw new IllegalStateException("No jar for the optional plugin " + name);
      }
      command.add("-add-plugin=" + jar);
    }
    if (optional.contains("bluemap")) {
      configureBlueMap(run, p.getPluginData().get().getAsFile().toPath().resolve("bluemap"));
    }
    LOG.info("[shard {}] {}", shard, String.join(" ", command));
    Duration limit = BOOT_ALLOWANCE.plus(PER_STEP.multipliedBy(segment.size()));
    try {
      Files.writeString(
          log,
          "\n===== shard " + shard + ": " + String.join(",", segment.stream().map(Step::label).toList()) + " =====\n",
          java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.APPEND);
      Process server =
          new ProcessBuilder(command)
              .directory(run.toFile())
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
              .redirectInput(ProcessBuilder.Redirect.PIPE)
              .start();
      server.getOutputStream().close();
      if (!server.waitFor(limit.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
        LOG.error("[shard {}] the server ran over {}; stopping it", shard, limit);
        server.descendants().forEach(ProcessHandle::destroyForcibly);
        server.destroyForcibly();
        server.waitFor();
      }
      // A crash point halts the server; the runner outlives it and finishes the session's checks.
      Path results = bootOut.resolve("results.json");
      long deadline = System.nanoTime() + HALT_RESULTS.toNanos();
      while (System.nanoTime() < deadline && !done(results)) {
        Thread.sleep(200);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static boolean done(Path results) {
    Map<String, Object> json = readJson(results);
    return json != null && Boolean.TRUE.equals(json.get("done"));
  }

  private static void writeServerProperties(Path run, int port) {
    String properties =
        """
        # Written by the scenario shard; never ships. Bots join in offline mode.
        online-mode=false
        enforce-secure-profile=false
        white-list=false
        server-ip=127.0.0.1
        server-port=%d
        level-type=minecraft:flat
        generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}
        generate-structures=false
        spawn-protection=0
        max-players=40
        view-distance=4
        simulation-distance=4
        """
            .formatted(port);
    try {
      Files.createDirectories(run);
      Files.writeString(run.resolve("server.properties"), properties);
      Files.writeString(run.resolve("eula.txt"), "eula=true\n");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Turns bStats off for every plugin on a scenario server, Seasons and Seasons Web included, before
   * it boots, so no test server reports to bstats.org. The server id bStats wrote before is kept.
   */
  static void turnOffBStats(Path run) {
    Path config = run.resolve("plugins/bStats/config.yml");
    try {
      String id = java.util.UUID.randomUUID().toString();
      if (Files.exists(config)) {
        java.util.regex.Matcher kept =
            java.util.regex.Pattern.compile("(?m)^serverUuid: *(\\S+)$")
                .matcher(Files.readString(config));
        if (kept.find()) {
          id = kept.group(1);
        }
      }
      Files.createDirectories(config.getParent());
      Files.writeString(
          config,
          """
          # Written by the scenario shard; never ships. No scenario server reports to bStats.
          enabled: false
          serverUuid: %s
          logFailedRequests: false
          """
              .formatted(id));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * BlueMap's settings for a scenario server (docs/adr/0004): it may download the game's client
   * jar, which it needs before its API starts, into a folder kept between runs; no web server or
   * web app, which the scenarios don't use and which would clash between shards; no metrics, no
   * skin downloads; one render thread.
   */
  private static void configureBlueMap(Path run, Path data) {
    Path config = run.resolve("plugins/BlueMap");
    deleteTree(config);
    deleteTree(run.resolve("bluemap"));
    mkdirs(config);
    mkdirs(data);
    try {
      Files.writeString(
          config.resolve("core.conf"),
          """
          # Written by the scenario shard; never ships.
          accept-download: true
          data: "%s"
          render-thread-count: 1
          scan-for-mod-resources: false
          metrics: false
          """
              .formatted(data.toAbsolutePath().toString().replace("\\", "/")));
      Files.writeString(config.resolve("webserver.conf"), "enabled: false\n");
      Files.writeString(config.resolve("webapp.conf"), "enabled: false\n");
      Files.writeString(config.resolve("plugin.conf"), "skin-download: false\n");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A fresh world and fresh Seasons files; Paper's own files and caches are kept. */
  private static void wipeWorld(Path run, Path state) {
    if (Files.isDirectory(run)) {
      try (Stream<Path> files = Files.list(run)) {
        for (Path f : files.toList()) {
          String name = f.getFileName().toString();
          if (name.startsWith("world")
              || name.equals("ops.json")
              || name.equals("whitelist.json")
              || name.equals("banned-players.json")
              || name.equals("banned-ips.json")
              || name.equals("usercache.json")) {
            deleteTree(f);
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    deleteTree(run.resolve("plugins/Seasons"));
    // An optional plugin's files, which the next boot may not have it for.
    deleteTree(run.resolve("plugins/BlueMap"));
    deleteTree(run.resolve("bluemap"));
    deleteTree(state);
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> readJson(Path file) {
    if (!Files.exists(file)) {
      return null;
    }
    try {
      return (Map<String, Object>) new JsonSlurper().parse(file.toFile());
    } catch (RuntimeException e) {
      return null; // half written
    }
  }

  static void writeJson(Path file, Object value) {
    try {
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      Files.writeString(tmp, JsonOutput.prettyPrint(JsonOutput.toJson(value)));
      Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void deleteTree(Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void mkdirs(Path dir) {
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
