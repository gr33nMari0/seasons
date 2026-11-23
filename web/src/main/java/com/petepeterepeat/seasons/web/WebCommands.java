// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import com.petepeterepeat.seasons.api.PlayerProfile;
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
 * What {@code /web link|sessions|logout|privacy} do, as lines to tell the player; {@link
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
    if (!web.serving()) {
      return off();
    }
    int limit = web.config().settings().rateLimits().wrongCodesPerMinutePerAccount();
    String key = "wrong-code:" + player;
    if (web.wrongCodes().check(key, limit) > 0) {
      web.audit().record(name, "web.link-locked", name, "refused", "too many wrong codes");
      return List.of(Line.of("link.locked"));
    }
    if (web.codes().link(code, new DeviceCodes.Linked(player, name, admin))) {
      web.audit().record(name, "web.login", name, "ok", "device code linked");
      return List.of(Line.of("link.linked", "player", name));
    }
    var _ = web.wrongCodes().take(key, limit);
    web.audit().record(name, "web.link-failed", name, "refused", "unknown or expired code");
    return List.of(Line.of("link.invalid"));
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
    for (SessionStore.Session s : mine) {
      lines.add(
          Line.of(
              "sessions.line", "id", s.id(), "device", s.device(), "seen", ago(now, s.lastSeen())));
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
      return CompletableFuture.completedFuture(
          List.of(Line.of("privacy.failed", "reason", "Seasons isn't running")));
    }
    SeasonsApi s = seasons.get();
    return s.profile(player)
        .thenCompose(profile -> {
          boolean hide = !profile.map(PlayerProfile::leaderboardHidden).orElse(false);
          return s.setLeaderboardHidden(player, hide)
              .thenApply(done -> List.of(Line.of(hide ? "privacy.hidden" : "privacy.shown")));
        })
        .exceptionally(e ->
            List.of(
                Line.of(
                    "privacy.failed",
                    "reason",
                    String.valueOf(
                        e.getCause() == null ? e.getMessage() : e.getCause().getMessage()))));
  }

  static String ago(Instant now, Instant then) {
    Duration d = Duration.between(then, now);
    if (d.compareTo(Duration.ofMinutes(1)) < 0) {
      return "just now";
    }
    if (d.compareTo(Duration.ofHours(1)) < 0) {
      return d.toMinutes() + " min ago";
    }
    if (d.compareTo(Duration.ofDays(1)) < 0) {
      return d.toHours() + " h ago";
    }
    return d.toDays() + " d ago";
  }
}
