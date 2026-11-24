// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import com.google.gson.JsonObject;
import com.petepeterepeat.seasons.api.SeasonsApi;
import com.petepeterepeat.seasons.api.event.SeasonsEvent;
import java.io.IOException;
import java.net.BindException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurateException;

/**
 * Seasons Web while its plugin is enabled: its files, sessions, codes, limits and event streams,
 * and the HTTP server, which runs only while web.yml says so and passes its checks. Never touches
 * the game: it reads Seasons API and nothing else.
 */
final class Web implements AutoCloseable {
  private final Path folder;
  private final Path data;
  private final Logger log;
  private final Clock clock;
  private final Supplier<Optional<SeasonsApi>> api;
  private final Supplier<Optional<Routes.Health>> health;
  private final Supplier<String> motd;
  private final String version;

  private final FileWriter writer;
  private final SessionStore sessions;
  private final DeviceCodes codes;
  private final RateLimiter limiter;
  private final RateLimiter wrongCodes;
  private final Audit audit;
  private final EventHub hub;

  private final Assets assets;
  private volatile @Nullable WebServer server;
  private volatile @Nullable MapProbe probe;
  private volatile WebConfig config;
  private volatile Messages messages;
  private volatile String name = "Seasons";
  private SeasonsApi.@Nullable Subscription subscription;

  /**
   * Seasons Web's state; nothing listens until {@link #reload}.
   *
   * @param folder {@code plugins/SeasonsWeb}
   * @param data {@code <world>/seasons-web}, for sessions and the audit log
   * @param health server health, sampled on the server thread
   * @param motd the server's MOTD as plain text, for a web.yml without a name
   */
  Web(
      Path folder,
      Path data,
      Logger log,
      Clock clock,
      Supplier<Optional<SeasonsApi>> api,
      Supplier<Optional<Routes.Health>> health,
      Supplier<String> motd,
      String version) {
    this.folder = folder;
    this.data = data;
    this.log = log;
    this.clock = clock;
    this.api = api;
    this.health = health;
    this.motd = motd;
    this.version = version;
    this.writer = new FileWriter(log);
    this.sessions = new SessionStore(data.resolve("sessions.json"), writer, clock, log);
    this.codes = new DeviceCodes(clock);
    this.limiter = new RateLimiter(clock);
    this.wrongCodes = new RateLimiter(clock);
    this.audit = new Audit(data.resolve("audit"), writer, clock);
    WebSettings defaults = SettingsFile.defaults();
    this.hub = new EventHub(limits(defaults));
    this.config = ((WebConfig.Ready) WebConfig.check(enabledCopy(defaults))).config();
    this.messages = loadMessages();
    this.assets = Assets.load();
  }

  /** Writes the default files and reads the sessions; then {@link #reload}. */
  void start() {
    try {
      SettingsFile.writeDefault(folder, SettingsFile.WEB);
      SettingsFile.writeDefault(folder, SettingsFile.MESSAGES);
    } catch (IOException e) {
      log.error("Can't write Seasons Web's default files", e);
    }
    messages = loadMessages();
    sessions.load();
  }

  /**
   * Reads web.yml and listens as it says, closing whatever listened before. Server thread.
   *
   * @return "listening on host:port", or "off (why)"
   */
  synchronized String reload() {
    stopServer();
    messages = loadMessages();
    WebSettings settings;
    try {
      settings = SettingsFile.load(folder);
    } catch (ConfigurateException e) {
      String reason = "web.yml doesn't read: " + e.getMessage();
      log.error("Seasons Web is off: {}", reason);
      return "off (" + reason + ")";
    }
    WebConfig.Result result = WebConfig.check(settings);
    result.warnings().forEach(w -> log.warn("web.yml {}", w));
    return switch (result) {
      case WebConfig.Off off -> {
        if (settings.enabled()) {
          log.error("Seasons Web is off: {}", off.reason());
        } else {
          log.info(
              "Seasons Web is off; set enabled: true in plugins/SeasonsWeb/web.yml to start it");
        }
        yield "off (" + off.reason() + ")";
      }
      case WebConfig.Ready ready -> listen(ready.config());
    };
  }

  private String listen(WebConfig next) {
    config = next;
    hub.limits(limits(next.settings()));
    String configured = next.settings().name().strip();
    name = configured.isEmpty() ? motd.get() : configured;
    MapProbe looking =
        next.map()
            .map(m -> new MapProbe(m.url(), next.publicUrl().map(WebConfig::origin), version, log))
            .orElse(null);
    Routes routes =
        new Routes(
            next,
            api,
            sessions,
            codes,
            limiter,
            health,
            () -> name,
            this::messages,
            version,
            clock,
            assets,
            () -> looking == null ? Framing.State.UNKNOWN : looking.state());
    try {
      WebServer started = new WebServer(next, routes, sessions, limiter, hub, log);
      started.start();
      server = started;
      if (looking != null) {
        looking.start();
        probe = looking;
      }
    } catch (BindException e) {
      String reason = "port " + next.bind().getPort() + " is in use on " + next.where();
      log.error("Seasons Web is off: {}", reason);
      return "off (" + reason + ")";
    } catch (IOException | RuntimeException e) {
      String reason = "can't listen on " + next.where() + ": " + e.getMessage();
      log.error("Seasons Web is off: {}", reason, e);
      return "off (" + reason + ")";
    }
    log.info(
        "Seasons Web listening on {}{}",
        next.where(),
        next.publicUrl().map(u -> " for " + u).orElse(""));
    return "listening on " + next.where();
  }

  private void stopServer() {
    MapProbe looking = probe;
    probe = null;
    if (looking != null) {
      looking.close();
    }
    WebServer running = server;
    server = null;
    if (running != null) {
      running.close();
    }
  }

  /** Follows Seasons API: the events of whichever Seasons is registered now. */
  synchronized void follow(Optional<SeasonsApi> seasons) {
    if (subscription != null) {
      subscription.close();
      subscription = null;
    }
    seasons.ifPresent(s -> subscription = s.subscribe(this::event));
  }

  private void event(SeasonsEvent event) {
    JsonObject o = new JsonObject();
    o.addProperty("at", event.at().toString());
    String type =
        switch (event) {
          case SeasonsEvent.PlayerJoined e -> {
            o.add("player", Routes.player(e.player()));
            yield "join";
          }
          case SeasonsEvent.SeasonFirst e -> {
            o.addProperty("season", e.season());
            o.addProperty("key", e.key());
            o.addProperty("label", e.label());
            o.add("player", Routes.player(e.player()));
            yield "first";
          }
          case SeasonsEvent.DragonSlain e -> {
            o.addProperty("season", e.season());
            e.slayer().ifPresent(p -> o.add("slayer", Routes.player(p)));
            yield "dragon";
          }
          case SeasonsEvent.ZoneEntered e -> {
            o.add("player", Routes.player(e.player()));
            o.addProperty("zone", e.zone());
            yield "zone";
          }
          case SeasonsEvent.PhaseChanged e -> {
            o.addProperty("season", e.season());
            o.addProperty("phase", e.phase());
            e.stage().ifPresent(s -> o.addProperty("stage", s));
            yield "phase";
          }
          case SeasonsEvent.HomecomingStarted e -> {
            o.addProperty("season", e.season());
            yield "homecoming";
          }
          case SeasonsEvent.PlayerArchived e -> {
            o.addProperty("season", e.season());
            o.add("player", Routes.player(e.player()));
            yield "archived";
          }
        };
    o.addProperty("type", type);
    hub.publish("event-feed", type, o);
  }

  /** Back to a fresh install, for the scenario reset: nothing listens and every file is gone. */
  synchronized void reset() {
    stopServer();
    writer.flush();
    codes.clear();
    limiter.clear();
    wrongCodes.clear();
    sessions.clear();
    deleteTree(data);
    deleteTree(folder);
    start();
    var _ = reload();
  }

  private void deleteTree(Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> files = Files.walk(root)) {
      for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    } catch (IOException e) {
      log.error("Can't delete {}", root, e);
    }
  }

  @Override
  public synchronized void close() {
    stopServer();
    if (subscription != null) {
      subscription.close();
      subscription = null;
    }
    sessions.save();
    writer.close();
  }

  private Messages loadMessages() {
    try {
      return Messages.load(folder);
    } catch (ConfigurateException e) {
      log.error("Can't read Seasons Web's messages.yml; using the bundled lines", e);
      try {
        return Messages.load(folder.resolve("missing"));
      } catch (ConfigurateException bundled) {
        throw new IllegalStateException("The bundled messages.yml doesn't read", bundled);
      }
    }
  }

  private static EventHub.Limits limits(WebSettings settings) {
    WebSettings.Stream s = settings.stream();
    return new EventHub.Limits(s.maxPerIp(), s.maxPerAccount(), s.maxTotal(), s.queue());
  }

  /** The defaults as a checked config, for the commands before anything listens. */
  private static WebSettings enabledCopy(WebSettings d) {
    return new WebSettings(
        d.schemaVersion(),
        true,
        d.bind(),
        d.publicUrl(),
        d.name(),
        d.gameAddresses(),
        d.open(),
        d.proxy(),
        d.links(),
        d.visibility(),
        d.sessions(),
        d.rateLimits(),
        d.stream(),
        d.cache(),
        d.screens());
  }

  boolean serving() {
    return server != null;
  }

  WebConfig config() {
    return config;
  }

  Messages messages() {
    return messages;
  }

  Logger log() {
    return log;
  }

  SessionStore sessions() {
    return sessions;
  }

  DeviceCodes codes() {
    return codes;
  }

  RateLimiter wrongCodes() {
    return wrongCodes;
  }

  Audit audit() {
    return audit;
  }

  EventHub hub() {
    return hub;
  }

  /** The port listened on, for tests. */
  int port() {
    WebServer running = server;
    return running == null ? -1 : running.port();
  }
}
