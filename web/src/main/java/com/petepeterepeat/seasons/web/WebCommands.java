// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import com.petepeterepeat.seasons.api.SeasonsApi;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * What {@code /web link|sessions|logout|privacy|discovery} do, as lines to tell the player; {@link
 * PaperWebCommands} wires them to Brigadier. Logins, failed codes and logouts are audited.
 */
final class WebCommands {
  private final Web web;
  private final Supplier<Optional<SeasonsApi>> api;
  private final Clock clock;

  WebCommands(Web web, Supplier<Optional<SeasonsApi>> api, Clock clock) {
    this.web = web;
    this.api = api;
    this.clock = clock;
  }

  private static List<Line> off() {
    return List.of(Line.of("dashboard-off"));
  }

  /** {@code /web link <code>}: the dashboard that showed the code logs in as this player. */
  List<Line> link(UUID player, String name, boolean admin, String code) {
    return link(player, name, Grants.of(admin), code);
  }

  /** {@code /web link <code>}, with the parts of seasons.web.admin the player holds. */
  List<Line> link(UUID player, String name, Grants grants, String code) {
    if (!web.serving()) {
      return off();
    }
    int limit = web.config().settings().rateLimits().wrongCodesPerMinutePerAccount();
    String key = "wrong-code:" + player;
    if (web.wrongCodes().check(key, limit) > 0) {
      web.audit().record(name, "web.link-locked", name, "refused", "too many wrong codes");
      return List.of(Line.of("link.locked"));
    }
    if (web.codes().link(code, new DeviceCodes.Linked(player, name, grants))) {
      web.audit().record(name, "web.login", name, "ok", "device code linked");
      return List.of(Line.of("link.linked", "player", name));
    }
    var _ = web.wrongCodes().take(key, limit);
    web.audit().record(name, "web.link-failed", name, "refused", "unknown or expired code");
    return List.of(Line.of("link.invalid"));
  }

  /**
   * {@code /web discovery}: the DNS records that let players find this dashboard (questions 336).
   */
  List<Line> discovery() {
    WebConfig config = web.config();
    return Discovery.records(config.gameAddresses(), config.publicUrl());
  }

  /**
   * {@code /web discovery check}: looks each record up and asks the dashboard, off the server
   * thread.
   */
  CompletableFuture<List<Line>> discoveryCheck(Discovery.Resolver resolver, Discovery.Asker asker) {
    WebConfig config = web.config();
    return Discovery.check(config.gameAddresses(), config.publicUrl(), resolver, asker);
  }

  /** {@code /web reload} (M10 plan, O1): applies web.yml if it checks out, else says why not. */
  List<Line> reload() {
    Web.Reloaded r = web.reloadChecked();
    List<Line> lines = new ArrayList<>();
    if (!r.applied()) {
      lines.add(Line.of(r.kept() ? "reload.kept" : "reload.stays-off"));
      r.problems().forEach(p -> lines.add(Line.of("reload.problem", "problem", p)));
      r.warnings().forEach(w -> lines.add(Line.of("reload.warning", "warning", w)));
      return lines;
    }
    lines.add(Line.of("reload.done", "state", r.state()));
    lines.add(
        r.changed().isEmpty()
            ? Line.of("reload.unchanged")
            : Line.of("reload.changed", "settings", String.join(", ", r.changed())));
    r.warnings().forEach(w -> lines.add(Line.of("reload.warning", "warning", w)));
    int missing = web.missingSettings();
    if (missing > 0) {
      lines.add(
          Line.of(
              "reload.missing",
              "count",
              Integer.toString(missing),
              "settings",
              missing == 1 ? "setting" : "settings"));
    }
    return lines;
  }

  /** {@code /web check} (M10 plan, O2), off the server thread once it has read the files. */
  CompletableFuture<List<Line>> check(
      WebCheck.Network net, Discovery.Resolver resolver, Discovery.Asker asker) {
    java.nio.file.Path file = web.folder().resolve(SettingsFile.WEB);
    String written;
    WebSettings settings;
    try {
      written = java.nio.file.Files.exists(file) ? java.nio.file.Files.readString(file) : "";
      settings = SettingsFile.load(web.folder());
    } catch (java.io.IOException e) {
      return CompletableFuture.completedFuture(
          List.of(
              Line.of("check.header"),
              Line.of(
                  "check.fail",
                  "area",
                  web.messages().text("check.area.settings"),
                  "text",
                  web.messages()
                      .text(
                          "check.text.settings.unreadable",
                          "why",
                          String.valueOf(e.getMessage())))));
    }
    WebCheck.State state =
        new WebCheck.State(
            written,
            SettingsFile.bundledText(SettingsFile.WEB),
            settings,
            WebConfig.check(settings),
            web.serving(),
            web.mapState(),
            web.selfCheck(),
            web.messages());
    return WebCheck.run(state, net, resolver, asker);
  }

  /** {@code /web upgrade [--dry-run]} (M10 plan, O3). */
  List<Line> upgrade(boolean dryRun) {
    java.nio.file.Path folder = web.folder();
    java.nio.file.Path file = folder.resolve(SettingsFile.WEB);
    List<String> missing;
    String written;
    try {
      if (!java.nio.file.Files.exists(file)) {
        return List.of(Line.of("upgrade.no-file"));
      }
      written = java.nio.file.Files.readString(file);
      missing = SettingsFile.missing(folder, SettingsFile.WEB);
    } catch (java.io.IOException e) {
      return List.of(Line.of("upgrade.unreadable", "why", String.valueOf(e.getMessage())));
    }
    if (missing.isEmpty()) {
      return List.of(Line.of("upgrade.nothing"));
    }
    String count = Integer.toString(missing.size());
    String word = missing.size() == 1 ? "setting" : "settings";
    if (dryRun) {
      return List.of(
          Line.of(
              "upgrade.would",
              "count",
              count,
              "settings",
              word,
              "list",
              String.join(", ", missing)),
          Line.of("upgrade.then"));
    }
    YamlUpgrade.Result r =
        YamlUpgrade.add(written, SettingsFile.bundledText(SettingsFile.WEB), missing);
    List<Line> lines = new ArrayList<>();
    if (!r.added().isEmpty()) {
      String backup;
      try {
        backup = backup(folder, written);
        java.nio.file.Path next = folder.resolve(SettingsFile.WEB + ".upgrading");
        java.nio.file.Files.writeString(next, r.text());
        java.nio.file.Files.move(
            next,
            file,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } catch (java.io.IOException e) {
        web.log().error("/web upgrade couldn't write web.yml", e);
        return List.of(Line.of("upgrade.unreadable", "why", String.valueOf(e.getMessage())));
      }
      String added = Integer.toString(r.added().size());
      lines.add(
          Line.of(
              "upgrade.added",
              "count",
              added,
              "settings",
              r.added().size() == 1 ? "setting" : "settings",
              "list",
              String.join(", ", r.added())));
      lines.add(Line.of("upgrade.backup", "file", backup));
    }
    r.byHand().forEach(b -> lines.add(Line.of("upgrade.by-hand", "why", b)));
    return lines;
  }

  /** Copies web.yml to web.yml.bak-<version>, numbered if one is there already; its name. */
  private String backup(java.nio.file.Path folder, String written) throws java.io.IOException {
    String base = SettingsFile.WEB + ".bak-" + web.version().replaceAll("[^0-9A-Za-z.-]", "-");
    String name = base;
    for (int n = 2; java.nio.file.Files.exists(folder.resolve(name)); n++) {
      name = base + "-" + n;
    }
    java.nio.file.Files.writeString(folder.resolve(name), written);
    return name;
  }

  List<Line> linkUsage() {
    return web.serving() ? List.of(Line.of("link.usage")) : off();
  }

  /** {@code /web sessions}: the player's logged-in devices, never their addresses. */
  List<Line> sessions(UUID player) {
    if (!web.serving()) {
      return off();
    }
    List<SessionStore.Session> mine = web.sessions().of(player);
    if (mine.isEmpty()) {
      return List.of(Line.of("sessions.none"));
    }
    List<Line> lines = new ArrayList<>();
    lines.add(Line.of("sessions.header", "count", Integer.toString(mine.size())));
    Instant now = clock.instant();
    Messages messages = web.messages();
    for (SessionStore.Session s : mine) {
      lines.add(
          Line.of(
              "sessions.line",
              "id",
              s.id(),
              "device",
              s.device(),
              "seen",
              ago(messages, now, s.lastSeen())));
    }
    lines.add(Line.of("sessions.hint"));
    return lines;
  }

  /** {@code /web logout [id|all]}. */
  List<Line> logout(UUID player, String name, Optional<String> target) {
    if (!web.serving()) {
      return off();
    }
    List<SessionStore.Session> mine = web.sessions().of(player);
    if (target.isPresent() && target.get().equalsIgnoreCase("all")) {
      int ended = web.sessions().revokeAll(player);
      web.hub().closeAccount(player);
      web.audit().record(name, "web.logout-all", name, "ok", ended + " sessions");
      return List.of(
          Line.of(
              "logout.all",
              "count",
              Integer.toString(ended),
              "devices",
              ended == 1 ? "device" : "devices"));
    }
    if (target.isEmpty()) {
      if (mine.size() == 1) {
        return logoutOne(player, name, mine.getFirst().id());
      }
      if (mine.isEmpty()) {
        return List.of(Line.of("sessions.none"));
      }
      return List.of(Line.of("logout.choose", "count", Integer.toString(mine.size())));
    }
    return logoutOne(player, name, target.get());
  }

  private List<Line> logoutOne(UUID player, String name, String id) {
    Optional<SessionStore.Session> ended = web.sessions().revoke(player, id);
    if (ended.isEmpty()) {
      return List.of(Line.of("logout.unknown", "id", id));
    }
    web.audit().record(name, "web.logout", name, "ok", ended.get().device());
    return List.of(Line.of("logout.one", "device", ended.get().device()));
  }

  /** {@code /web privacy}: hidden from leaderboards, or shown again. */
  CompletableFuture<List<Line>> privacy(UUID player) {
    if (!web.serving()) {
      return CompletableFuture.completedFuture(off());
    }
    Optional<SeasonsApi> seasons = api.get();
    if (seasons.isEmpty()) {
      return CompletableFuture.completedFuture(List.of(Line.of("privacy.unavailable")));
    }
    SeasonsApi s = seasons.get();
    return s.profile(player)
        .thenCompose(profile -> {
          if (profile.isEmpty()) {
            return CompletableFuture.completedFuture(List.of(Line.of("privacy.no-record")));
          }
          boolean hide = !profile.get().leaderboardHidden();
          return s.setLeaderboardHidden(player, hide)
              .thenApply(done -> List.of(Line.of(hide ? "privacy.hidden" : "privacy.shown")));
        })
        .exceptionally(e -> {
          // The player is told where to look; why goes to the log, never into chat.
          web.log().warn("/web privacy failed for {}", player, e);
          return List.of(Line.of("privacy.failed"));
        });
  }

  /** How long ago, in messages.yml's words, such as "10 min ago". */
  static String ago(Messages messages, Instant now, Instant then) {
    Duration d = Duration.between(then, now);
    if (d.compareTo(Duration.ofMinutes(1)) < 0) {
      return messages.text("sessions.seen.just-now");
    }
    if (d.compareTo(Duration.ofHours(1)) < 0) {
      return messages.text("sessions.seen.minutes", "count", Long.toString(d.toMinutes()));
    }
    if (d.compareTo(Duration.ofDays(1)) < 0) {
      return messages.text("sessions.seen.hours", "count", Long.toString(d.toHours()));
    }
    return messages.text("sessions.seen.days", "count", Long.toString(d.toDays()));
  }
}
