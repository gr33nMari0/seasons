// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.petepeterepeat.seasons.api.ArchiveView;
import com.petepeterepeat.seasons.api.AuditEntry;
import com.petepeterepeat.seasons.api.GalleryView;
import com.petepeterepeat.seasons.api.LeaderboardEntry;
import com.petepeterepeat.seasons.api.Leaderboards;
import com.petepeterepeat.seasons.api.MuseumView;
import com.petepeterepeat.seasons.api.OnlinePlayer;
import com.petepeterepeat.seasons.api.PendingPlayer;
import com.petepeterepeat.seasons.api.PlayerName;
import com.petepeterepeat.seasons.api.PlayerProfile;
import com.petepeterepeat.seasons.api.SeasonSummary;
import com.petepeterepeat.seasons.api.SeasonView;
import com.petepeterepeat.seasons.api.SeasonsApi;
import com.petepeterepeat.seasons.api.WaypointView;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every endpoint but the event stream (spec, API): read-only, JSON, under {@code /api/v1}, each
 * checked against the viewer's access and filtered field by field by web.yml's visibility. Nothing
 * here touches the game: it reads Seasons API's snapshots and views.
 */
final class Routes {
  static final String WELL_KNOWN = "/.well-known/seasons.json";
  static final String EVENTS = "/api/v1/events";
  static final String SESSION_COOKIE = "__Host-seasons_session";
  static final String DEVICE_COOKIE = "__Host-seasons_device";

  private static final Pattern PLAYER = Pattern.compile("/api/v1/players/([^/]+)");
  private static final Pattern HALL = Pattern.compile("/api/v1/hall/(\\d{1,4})");
  private static final Pattern HALL_PLAYER = Pattern.compile("/api/v1/hall/(\\d{1,4})/([^/]+)");
  private static final long WAIT_SECONDS = 10;

  /** How many of the audit log's last entries an admin sees. */
  static final int AUDIT_ENTRIES = 50;

  /** Server health, sampled on the server thread. */
  record Health(List<Double> tps, double tickMs, long usedBytes, long maxBytes) {
    Health {
      tps = List.copyOf(tps);
    }
  }

  /** What a request brings. */
  record Request(
      String method,
      String path,
      Map<String, String> query,
      Viewer viewer,
      String ip,
      Map<String, String> cookies,
      String userAgent,
      long bodyLength,
      String ifNoneMatch) {}

  private final WebConfig config;
  private final Supplier<Optional<SeasonsApi>> api;
  private final SessionStore sessions;
  private final DeviceCodes codes;
  private final RateLimiter limiter;
  private final Supplier<Optional<Health>> health;
  private final Supplier<String> serverName;
  private final Supplier<Messages> messages;
  private final String version;
  private final Clock clock;
  private final Assets assets;
  private final Supplier<Framing.State> framing;
  private final Optional<String> rules;
  private final Previews previews;

  Routes(
      WebConfig config,
      Supplier<Optional<SeasonsApi>> api,
      SessionStore sessions,
      DeviceCodes codes,
      RateLimiter limiter,
      Supplier<Optional<Health>> health,
      Supplier<String> serverName,
      Supplier<Messages> messages,
      String version,
      Clock clock,
      Assets assets,
      Supplier<Framing.State> framing,
      Optional<String> rules,
      Previews previews) {
    this.config = config;
    this.api = api;
    this.sessions = sessions;
    this.codes = codes;
    this.limiter = limiter;
    this.health = health;
    this.serverName = serverName;
    this.messages = messages;
    this.version = version;
    this.clock = clock;
    this.assets = assets;
    this.framing = framing;
    this.rules = rules;
    this.previews = previews;
  }

  /** A short link to a player's profile: /p/<name>. */
  private static final java.util.regex.Pattern SHORT =
      java.util.regex.Pattern.compile("/p/([A-Za-z0-9_]{1,16})");

  Reply route(Request r) {
    Optional<Assets.Asset> asset = assets.find(r.path());
    if (asset.isPresent() && asset.get().contentType().startsWith("text/html")) {
      // A page: the same file for every address, with that address's preview tags (questions 337).
      return method(r, "GET")
          .orElseGet(() -> {
            String html =
                asset.get().body().replace("<!--seasons:head-->", previews.head(r.path()));
            Assets.Asset page =
                new Assets.Asset(
                    asset.get().contentType(),
                    html,
                    Assets.etag(html.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return Assets.reply(
                page, r.ifNoneMatch().isEmpty() ? Optional.empty() : Optional.of(r.ifNoneMatch()));
          });
    }
    if (r.path().startsWith("/og/")) {
      return method(r, "GET")
          .orElseGet(() -> previews.image(r.path()).orElseGet(() -> Reply.error(404, "not-found")));
    }
    java.util.regex.Matcher shortLink = SHORT.matcher(r.path());
    if (shortLink.matches()) {
      return method(r, "GET").orElseGet(() -> shortLink(shortLink.group(1)));
    }
    if (asset.isPresent()) {
      // The page and its files are the same for everyone.
      return method(r, "GET")
          .orElseGet(() ->
              Assets.reply(
                  asset.get(),
                  r.ifNoneMatch().isEmpty() ? Optional.empty() : Optional.of(r.ifNoneMatch())));
    }
    Reply reply = dispatch(r);
    // A response to a logged-in player depends on who they are: never cached for anyone else.
    return r.viewer() instanceof Viewer.Player && !r.path().equals(WELL_KNOWN)
        ? reply.privately()
        : reply;
  }

  private Reply dispatch(Request r) {
    String path = r.path();
    if (path.equals(WELL_KNOWN)) {
      return method(r, "GET").orElseGet(this::wellKnown);
    }
    if (path.equals("/api/v1/auth/device")) {
      return method(r, "POST").orElseGet(() -> device(r));
    }
    if (path.equals("/api/v1/auth/poll")) {
      return method(r, "GET").orElseGet(() -> poll(r));
    }
    if (path.equals("/api/v1/season")) {
      return method(r, "GET").orElseGet(() -> season(r.viewer()));
    }
    if (path.equals("/api/v1/players/online")) {
      return method(r, "GET").orElseGet(() -> online(r.viewer()));
    }
    if (path.equals("/api/v1/leaderboards")) {
      return method(r, "GET").orElseGet(() -> leaderboards(r));
    }
    if (path.equals("/api/v1/waypoints")) {
      return method(r, "GET").orElseGet(() -> waypoints(r.viewer()));
    }
    if (path.equals("/api/v1/links")) {
      return method(r, "GET").orElseGet(() -> links(r.viewer()));
    }
    if (path.equals("/api/v1/admin/health")) {
      return method(r, "GET").orElseGet(() -> health(r.viewer()));
    }
    if (path.equals("/api/v1/dashboard")) {
      return method(r, "GET").orElseGet(() -> dashboard(r.viewer()));
    }
    if (path.equals("/api/v1/seasons")) {
      return method(r, "GET").orElseGet(() -> seasons(r.viewer()));
    }
    if (path.equals("/api/v1/museum")) {
      return method(r, "GET").orElseGet(() -> museum(r.viewer()));
    }
    if (path.equals("/api/v1/admin/pending")) {
      return method(r, "GET").orElseGet(() -> pending(r.viewer()));
    }
    if (path.equals("/api/v1/admin/audit")) {
      return method(r, "GET").orElseGet(() -> audit(r.viewer()));
    }
    Matcher m = HALL_PLAYER.matcher(path);
    if (m.matches()) {
      return method(r, "GET")
          .orElseGet(() -> hallPlayer(r.viewer(), Integer.parseInt(m.group(1)), m.group(2)));
    }
    Matcher hall = HALL.matcher(path);
    if (hall.matches()) {
      return method(r, "GET").orElseGet(() -> hall(r.viewer(), Integer.parseInt(hall.group(1))));
    }
    Matcher player = PLAYER.matcher(path);
    if (player.matches()) {
      return method(r, "GET").orElseGet(() -> profile(r.viewer(), player.group(1)));
    }
    return Reply.error(404, "not-found");
  }

  private static Optional<Reply> method(Request r, String allowed) {
    if (!r.method().equals(allowed)) {
      return Optional.of(Reply.error(405, "method-not-allowed").withHeader("Allow", allowed));
    }
    if (r.bodyLength() > 0) {
      return Optional.of(Reply.error(413, "no-body-expected"));
    }
    return Optional.empty();
  }

  /** Whether a viewer may see a key: empty if so, else 404 (hidden), 401 or 403. */
  Optional<Reply> deny(String key, Viewer viewer) {
    if (!config.visibility().shown(key)) {
      return Optional.of(Reply.error(404, "not-found"));
    }
    if (config.visibility().allows(key, viewer)) {
      return Optional.empty();
    }
    return Optional.of(
        viewer instanceof Viewer.Anonymous
            ? Reply.error(401, "unauthorized")
            : Reply.error(403, "forbidden"));
  }

  private boolean sees(String key, Viewer viewer) {
    return config.visibility().allows(key, viewer);
  }

  // The page's own settings

  /** Widgets the page has but the API has no key of its own for: they show with another's. */
  private static final Map<String, String> PAGE_ONLY = Map.of("rules", "season");

  /**
   * What the page needs to draw itself for this viewer: who they are, the widgets and fields they
   * may see, the map and whether it can be framed, the second screens, and the page's text from
   * messages.yml.
   */
  private Reply dashboard(Viewer viewer) {
    JsonObject o = new JsonObject();
    o.addProperty("name", serverName.get());
    o.addProperty("version", version);
    o.addProperty("public", config.visibility().open());
    JsonObject who = new JsonObject();
    if (viewer instanceof Viewer.Player p) {
      who.addProperty("kind", "player");
      who.addProperty("uuid", p.id().toString());
      who.addProperty("name", p.name());
      who.addProperty("admin", p.admin());
      JsonArray grants = new JsonArray();
      p.grants().held().stream().sorted().forEach(g -> grants.add(g.name().toLowerCase(java.util.Locale.ROOT)));
      who.add("grants", grants);
    } else {
      who.addProperty("kind", "anonymous");
    }
    o.add("viewer", who);
    JsonArray widgets = new JsonArray();
    for (String w : Visibility.WIDGETS) {
      if (sees(w, viewer)) {
        widgets.add(w);
      }
    }
    PAGE_ONLY.forEach((w, key) -> {
      if (sees(key, viewer)) {
        widgets.add(w);
      }
    });
    o.add("widgets", widgets);
    JsonObject fields = new JsonObject();
    for (String key : Visibility.DEFAULTS.keySet()) {
      fields.addProperty(key, sees(key, viewer));
    }
    o.add("fields", fields);
    Optional<WebSettings.Link> map = config.map();
    if (map.isPresent() && sees("map", viewer)) {
      JsonObject m = new JsonObject();
      m.addProperty("label", map.get().label());
      m.addProperty("url", map.get().url());
      m.addProperty("framing", framing.get().text());
      o.add("map", m);
    } else {
      o.add("map", JsonNull.INSTANCE);
    }
    JsonObject screens = new JsonObject();
    config.screens().forEach((name, list) -> screens.add(name, strings(list)));
    o.add("screens", screens);
    o.add("help", help());
    o.addProperty("site", SITE);
    o.addProperty("sessionDays", Math.max(1, config.settings().sessions().lifetime().toDays()));
    o.addProperty("refresh", 30);
    JsonObject texts = new JsonObject();
    messages.get().section("dashboard").forEach(texts::addProperty);
    o.add("texts", texts);
    o.addProperty("now", clock.instant().toString());
    return Reply.ok(o);
  }

  /** /p/<name>: to that player's profile, or home when there is no such player. */
  private Reply shortLink(String name) {
    Optional<java.util.UUID> id = Optional.empty();
    Optional<SeasonsApi> seasons = api.get();
    if (seasons.isPresent()) {
      try {
        id = seasons.get().playerId(name).get(3, java.util.concurrent.TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
        id = Optional.empty();
      }
    }
    String to = id.map(u -> "/player/" + u).orElse("/");
    return new Reply(
        302, "text/plain; charset=utf-8", "", Map.of("Location", to), List.of(), false);
  }

  /** The public site, for the login page's "Wrong server?" (its connect page). */
  static final String SITE = "https://seasonsmc.petepeterepeat.com/";

  /** Where the public players' guides live, for "Full docs" links on the Help page. */
  static final String DOCS = SITE + "docs/";

  /**
   * The Help page: the players' guides bundled from the public docs (their files, at {@code
   * /app/help/<name>.md}, with each one's title), and this server's own links and rules from
   * web.yml. Public: it holds nothing about anyone.
   */
  private JsonObject help() {
    JsonObject h = new JsonObject();
    JsonArray guides = new JsonArray();
    for (String file : assets.under("help/")) {
      JsonObject g = new JsonObject();
      String name = file.substring("help/".length(), file.length() - ".md".length());
      g.addProperty("name", name);
      g.addProperty("file", Assets.PREFIX + file);
      g.addProperty("title", assets.text(file).flatMap(Routes::title).orElse(name));
      g.addProperty("docs", DOCS + "players/" + name + "/");
      guides.add(g);
    }
    h.add("guides", guides);
    JsonArray links = new JsonArray();
    for (WebSettings.Link link : config.helpLinks()) {
      JsonObject l = new JsonObject();
      l.addProperty("label", link.label());
      l.addProperty("url", link.url());
      links.add(l);
    }
    h.add("links", links);
    if (rules.isPresent()) {
      h.addProperty("rules", rules.get());
    } else {
      h.add("rules", JsonNull.INSTANCE);
    }
    h.addProperty("docs", DOCS);
    return h;
  }

  /** A Markdown file's title, from its front matter. */
  static Optional<String> title(String markdown) {
    if (!markdown.startsWith("---")) {
      return Optional.empty();
    }
    int end = markdown.indexOf("\n---", 3);
    String front = end < 0 ? "" : markdown.substring(3, end);
    return front
        .lines()
        .filter(l -> l.startsWith("title:"))
        .map(l -> l.substring("title:".length()).strip().replaceAll("^[\"']|[\"']$", ""))
        .findFirst();
  }

  private Reply wellKnown() {
    JsonObject o = new JsonObject();
    o.addProperty("service", "seasons");
    o.addProperty("name", serverName.get());
    o.addProperty("version", version);
    Optional<SeasonsApi> seasons = api.get();
    o.addProperty("seasonsVersion", seasons.map(SeasonsApi::version).orElse(""));
    o.addProperty("api", "v1");
    o.addProperty("apiVersion", seasons.map(SeasonsApi::apiVersion).orElse(""));
    if (config.publicUrl().isPresent()) {
      o.addProperty("dashboard", config.publicUrl().get().toString());
    } else {
      o.add("dashboard", JsonNull.INSTANCE);
    }
    o.addProperty("public", config.visibility().open());
    // The addresses players join with, so the public connect page can confirm a dashboard it
    // found from one is this server's (questions 336).
    JsonArray games = new JsonArray();
    config.gameAddresses().forEach(games::add);
    o.add("gameAddresses", games);
    JsonArray features = new JsonArray();
    Visibility.WIDGETS.stream().filter(config.visibility()::shown).forEach(features::add);
    o.add("features", features);
    JsonArray login = new JsonArray();
    login.add("device-code");
    o.add("login", login);
    return Reply.ok(o).withHeader("Access-Control-Allow-Origin", "*");
  }

  // Login

  private Reply device(Request r) {
    long wait =
        limiter.take(
            "device:" + r.ip(), config.settings().rateLimits().deviceCodesPerMinutePerIp());
    if (wait > 0) {
      return Reply.error(429, "rate-limited")
          .withHeader("Retry-After", Long.toString(wait))
          .privately();
    }
    DeviceCodes.Issued issued = codes.issue();
    JsonObject o = new JsonObject();
    o.addProperty("code", issued.shown());
    o.addProperty("expiresIn", DeviceCodes.LIFETIME.toSeconds());
    o.addProperty("interval", DeviceCodes.INTERVAL.toSeconds());
    return Reply.ok(o)
        .withCookie(cookie(DEVICE_COOKIE, issued.deviceSecret(), DeviceCodes.LIFETIME.toSeconds()));
  }

  private Reply poll(Request r) {
    String secret = r.cookies().getOrDefault(DEVICE_COOKIE, "");
    if (secret.isEmpty()) {
      return Reply.error(400, "no-device-code").privately();
    }
    return switch (codes.poll(secret)) {
      case DeviceCodes.Waiting w -> status(202, "pending").privately();
      case DeviceCodes.TooSoon t ->
          status(429, "slow-down")
              .withHeader("Retry-After", Long.toString(DeviceCodes.INTERVAL.toSeconds()))
              .privately();
      case DeviceCodes.Expired e -> status(410, "expired").withCookie(cookie(DEVICE_COOKIE, "", 0));
      case DeviceCodes.Unknown u -> status(410, "expired").withCookie(cookie(DEVICE_COOKIE, "", 0));
      case DeviceCodes.Collected c -> {
        SessionStore.Created created =
            sessions.create(
                c.linked().player(),
                c.linked().name(),
                Devices.label(messages.get(), r.userAgent()),
                c.linked().grants(),
                config.sessionLifetime());
        JsonObject o = new JsonObject();
        o.addProperty("status", "linked");
        o.add("player", player(new PlayerName(c.linked().player(), c.linked().name())));
        yield Reply.ok(o)
            .withCookie(
                cookie(SESSION_COOKIE, created.token(), config.sessionLifetime().toSeconds()))
            .withCookie(cookie(DEVICE_COOKIE, "", 0));
      }
    };
  }

  private static Reply status(int code, String status) {
    JsonObject o = new JsonObject();
    o.addProperty("status", status);
    return Reply.json(code, o);
  }

  /** A cookie only this site's own pages send back, never readable by scripts. */
  static String cookie(String name, String value, long maxAge) {
    return name
        + "="
        + value
        + "; Path=/; Max-Age="
        + maxAge
        + "; HttpOnly; Secure; SameSite=Strict";
  }

  // The season and who's online

  private Reply season(Viewer viewer) {
    Optional<Reply> denied = deny("season", viewer);
    if (denied.isPresent()) {
      return denied.get();
    }
    Optional<SeasonsApi> seasons = api.get();
    if (seasons.isEmpty()) {
      return unavailable();
    }
    JsonObject o = new JsonObject();
    Optional<SeasonView> view = seasons.get().season();
    if (view.isEmpty()) {
      o.add("season", JsonNull.INSTANCE);
      o.addProperty("phase", "none");
    } else {
      SeasonView s = view.get();
      o.addProperty("season", s.season());
      o.addProperty("phase", s.phase());
      s.stage().ifPresent(v -> o.addProperty("stage", v));
      s.plannedEnd().ifPresent(v -> o.addProperty("plannedEnd", v.toString()));
      s.countdownEndsAt().ifPresent(v -> o.addProperty("countdownEndsAt", v.toString()));
      s.launchedAt().ifPresent(v -> o.addProperty("launchedAt", v.toString()));
      s.homecomingAt().ifPresent(v -> o.addProperty("homecomingAt", v.toString()));
      s.plannedHomecoming().ifPresent(v -> o.addProperty("plannedHomecoming", v.toString()));
      JsonArray zones = new JsonArray();
      for (SeasonView.ZoneView z : s.zones()) {
        JsonObject zone = new JsonObject();
        zone.addProperty("name", z.name());
        zone.addProperty("mode", z.mode());
        zones.add(zone);
      }
      o.add("zones", zones);
    }
    o.addProperty("now", clock.instant().toString());
    return Reply.ok(o);
  }

  private Reply online(Viewer viewer) {
    Optional<Reply> denied = deny("online", viewer);
    if (denied.isPresent()) {
      return denied.get();
    }
    Optional<SeasonsApi> seasons = api.get();
    if (seasons.isEmpty()) {
      return unavailable();
    }
    JsonArray list = new JsonArray();
    for (OnlinePlayer p : seasons.get().online()) {
      JsonObject o = new JsonObject();
      o.addProperty("uuid", p.id().toString());
      o.addProperty("name", p.name());
      p.skin().ifPresent(s -> o.addProperty("skin", s));
      if (sees("online.dimension", viewer)) {
        o.addProperty("dimension", p.dimension());
      }
      if (sees("online.region", viewer)) {
        JsonObject region = new JsonObject();
        region.addProperty("kind", p.region().kind());
        region.addProperty("name", p.region().name());
        o.add("region", region);
      }
      if (sees("online.afk", viewer)) {
        o.addProperty("afk", p.afk());
      }
      list.add(o);
    }
    JsonObject o = new JsonObject();
    o.add("players", list);
    return Reply.ok(o);
  }

  // Profiles

  private Reply profile(Viewer viewer, String id) {
    Optional<UUID> uuid = uuid(id);
    if (uuid.isEmpty()) {
      return Reply.error(404, "not-found");
    }
    boolean own = viewer instanceof Viewer.Player p && p.id().equals(uuid.get());
    boolean full = own || sees("profiles", viewer);
    if (!full) {
      Optional<Reply> denied = deny("profiles.summary", viewer);
      if (denied.isPresent()) {
        return denied.get();
      }
    }
    Optional<SeasonsApi> seasons = api.get();
    if (seasons.isEmpty()) {
      return unavailable();
    }
    Optional<PlayerProfile> found = await(seasons.get().profile(uuid.get())).flatMap(p -> p);
    if (found.isEmpty()) {
      return Reply.error(404, "not-found");
    }
    PlayerProfile p = found.get();
    JsonObject o = new JsonObject();
    o.addProperty("uuid", p.id().toString());
    o.addProperty("name", p.name());
    p.skin().ifPresent(s -> o.addProperty("skin", s));
    JsonArray list = new JsonArray();
    for (PlayerProfile.PlayedSeason s : p.seasons()) {
      JsonObject season = new JsonObject();
      season.addProperty("season", s.season());
      s.cell().ifPresent(c -> season.addProperty("cell", c));
      season.add("awards", strings(s.awards()));
      season.add("firsts", firsts(s.firsts()));
      if (full) {
        season.addProperty("firstSeen", s.firstSeen().toString());
        season.addProperty("lastSeen", s.lastSeen().toString());
        season.addProperty("playTime", s.playTime().toSeconds());
        JsonObject stats = new JsonObject();
        s.statistics().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> stats.addProperty(e.getKey(), e.getValue()));
        season.add("statistics", stats);
      }
      list.add(season);
    }
    o.add("seasons", list);
    if (own) {
      o.addProperty("leaderboardHidden", p.leaderboardHidden());
    }
    return Reply.ok(o);
  }

  // The Hall

  private Reply hall(Viewer viewer, int number) {
    Optional<Reply> denied = deny("hall", viewer);
    if (denied.isPresent()) {
      return denied.get();
    }
    Optional<SeasonsApi> seasons = api.get();
    if (seasons.isEmpty()) {
      return unavailable();
    }
    Optional<GalleryView> gallery = await(seasons.get().gallery(number)).flatMap(g -> g);
    if (gallery.isEmpty()) {
      return Reply.error(404, "not-found");
    }
    JsonArray cells = new JsonArray();
    for (GalleryView.CellView c : gallery.get().cells()) {
      JsonObject cell = new JsonObject();
      cell.addProperty("gallery", c.gallery());
      cell.addProperty("cell", c.cell());
      if (c.owner().isPresent()) {
        cell.add("owner", player(c.owner().get()));
      } else {
        cell.add("owner", JsonNull.INSTANCE);
      }
      cell.add("awards", strings(c.awards()));
      cells.add(cell);
    }
    JsonObject o = new JsonObject();
    o.addProperty("season", number);
    o.add("cells", cells);
    return Reply.ok(o);
  }

  private Reply hallPlayer(Viewer viewer, int number, String id) {
    Optional<Reply> denied = deny("hall", viewer);
    if (denied.isPresent()) {
      return denied.get();
    }
    Optional<UUID> uuid = uuid(id);
    if (uuid.isEmpty()) {
      return Reply.error(404, "not-found");
    }
    Optional<SeasonsApi> seasons = api.get();
    if (seasons.isEmpty()) {
      return unavailable();
    }
    Optional<ArchiveView> archive =
        await(seasons.get().archive(number, uuid.get())).flatMap(a -> a);
    if (archive.isEmpty()) {
      return Reply.error(404, "not-found");
    }
    ArchiveView a = archive.get();
    JsonObject o = new JsonObject();
    o.addProperty("season", number);
    o.addProperty("uuid", a.player().id().toString());
    o.addProperty("name", a.player().name());
    o.addProperty("created", a.created().toString());
    a.cell().ifPresent(c -> o.addProperty("cell", c));
    o.add("awards", strings(a.awards()));
    o.add("firsts", firsts(a.firsts()));
    if (sees("hall.gear", viewer)) {
      JsonArray gear = new JsonArray();
      for (ArchiveView.ItemView item : a.items()) {
        gear.add(item(item));
      }
      o.add("gear", gear);
    }
    return Reply.ok(o);
  }

  /** An item as text: its section, id, count, name, enchantments and trim. */
  static JsonObject item(ArchiveView.ItemView item) {
    JsonObject i = new JsonObject();
    i.addProperty("section", item.section());
    i.addProperty("id", item.id());
    i.addProperty("count", item.count());
    item.name().ifPresent(n -> i.addProperty("name", n));
    JsonArray enchantments = new JsonArray();
    for (ArchiveView.Enchantment e : item.enchantments()) {
      JsonObject en = new JsonObject();
      en.addProperty("id", e.id());
      en.addProperty("level", e.level());
      enchantments.add(en);
    }
    i.add("enchantments", enchantments);
    item.trim()
        .ifPresent(t -> {
          JsonObject trim = new JsonObject();
          trim.addProperty("pattern", t.pattern());
          trim.addProperty("material", t.material());
          i.add("trim", trim);
        });
    return i;
  }

  // Seasons, the museum and the admin lists (Seasons API 1.3)

  private Reply seasons(Viewer viewer) {
    Optional<Reply> denied = deny("history", viewer);
    if (denied.isPresent()) {
      return denied.get();
    }
    Optional<SeasonsApi> seasons = api.get();
    if (seasons.isEmpty()) {
      return unavailable();
    }
    Optional<List<SeasonSummary>> list = await(seasons.get().seasons());
    if (list.isEmpty()) {
      return unavailable();
    }
    JsonArray out = new JsonArray();
    for (SeasonSummary s : list.get()) {
      JsonObject o = new JsonObject();
      o.addProperty("season", s.season());
      o.addProperty("current", s.current());
      s.firstSeen().ifPresent(v -> o.addProperty("firstSeen", v.toString()));
      s.lastSeen().ifPresent(v -> o.addProperty("lastSeen", v.toString()));
      o.addProperty("players", s.players());
      o.addProperty("firsts", s.firsts());
      o.addProperty("gallery", s.gallery());
      out.add(o);
    }
    JsonObject o = new JsonObject();
    o.add("seasons", out);
    return Reply.ok(o);
  }

  private Reply museum(Viewer viewer) {
    Optional<Reply> denied = deny("museum", viewer);
    if (denied.isPresent()) {
      return denied.get();
    }
    Optional<SeasonsApi> seasons = api.get();
    if (seasons.isEmpty()) {
      return unavailable();
    }
    Optional<MuseumView> museum = await(seasons.get().museum());
    if (museum.isEmpty()) {
      return unavailable();
    }
    JsonArray trophies = new JsonArray();
    for (MuseumView.Trophy t : museum.get().trophies()) {
      JsonObject o = new JsonObject();
      o.addProperty("anchor", t.anchor());
      t.label().ifPresent(l -> o.addProperty("label", l));
      o.addProperty("season", t.season());
      o.addProperty("credit", t.credit());
      o.addProperty("at", t.at().toString());
      t.item().ifPresent(i -> o.add("item", item(i)));
      trophies.add(o);
    }
    JsonArray eggs = new JsonArray();
    for (MuseumView.Egg e : museum.get().eggs()) {
      JsonObject o = new JsonObject();
      o.addProperty("season", e.season());
      o.add("slayer", player(e.slayer()));
      o.addProperty("at", e.at().toString());
      eggs.add(o);
    }
    JsonObject o = new JsonObject();
    o.add("trophies", trophies);
    o.add("eggs", eggs);
    return Reply.ok(o);
  }

  private Reply pending(Viewer viewer) {
    Optional<Reply> denied = deny("pending-players", viewer);
    if (denied.isPresent()) {
      return denied.get();
    }
    Optional<SeasonsApi> seasons = api.get();
    if (seasons.isEmpty()) {
      return unavailable();
    }
    Optional<List<PendingPlayer>> list = await(seasons.get().pending());
    if (list.isEmpty()) {
      return unavailable();
    }
    JsonArray out = new JsonArray();
    for (PendingPlayer p : list.get()) {
      JsonObject o = player(p.player());
      o.addProperty("season", p.season());
      out.add(o);
    }
    JsonObject o = new JsonObject();
    o.add("players", out);
    return Reply.ok(o);
  }

  private Reply audit(Viewer viewer) {
    Optional<Reply> denied = deny("audit-log", viewer);
    if (denied.isPresent()) {
      return denied.get();
    }
    Optional<SeasonsApi> seasons = api.get();
    if (seasons.isEmpty()) {
      return unavailable();
    }
    Optional<List<AuditEntry>> list = await(seasons.get().audit(AUDIT_ENTRIES));
    if (list.isEmpty()) {
      return unavailable();
    }
    JsonArray out = new JsonArray();
    for (AuditEntry e : list.get()) {
      JsonObject o = new JsonObject();
      o.addProperty("time", e.time().toString());
      o.addProperty("actor", e.actor());
      o.addProperty("action", e.action());
      o.addProperty("target", e.target());
      o.addProperty("result", e.result());
      out.add(o);
    }
    JsonObject o = new JsonObject();
    o.add("entries", out);
    return Reply.ok(o);
  }

  // Leaderboards

  private Reply leaderboards(Request r) {
    Viewer viewer = r.viewer();
    Optional<Reply> denied = deny("leaderboards", viewer);
    if (denied.isPresent()) {
      return denied.get();
    }
    Optional<SeasonsApi> seasons = api.get();
    if (seasons.isEmpty()) {
      return unavailable();
    }
    String asked = r.query().getOrDefault("season", "");
    int number;
    if (asked.isEmpty()) {
      OptionalInt current = seasons.get().currentSeason();
      if (current.isEmpty()) {
        // No season yet: nothing to rank.
        JsonObject empty = new JsonObject();
        empty.add("season", JsonNull.INSTANCE);
        JsonObject boards = new JsonObject();
        for (String board : List.of("deaths", "play-time", "distance")) {
          boards.add(board, new JsonArray());
        }
        empty.add("boards", boards);
        return Reply.ok(empty);
      }
      number = current.getAsInt();
    } else {
      try {
        number = Integer.parseInt(asked);
      } catch (NumberFormatException e) {
        return Reply.error(400, "bad-season");
      }
    }
    Optional<Leaderboards> boards = await(seasons.get().leaderboards(number));
    if (boards.isEmpty()) {
      return unavailable();
    }
    JsonObject all = new JsonObject();
    all.add("deaths", board(boards.get().deaths()));
    all.add("play-time", board(boards.get().playTime()));
    all.add("distance", board(boards.get().distance()));
    JsonObject o = new JsonObject();
    o.addProperty("season", number);
    o.add("boards", all);
    if (viewer instanceof Viewer.Player p) {
      boolean hidden =
          boards.get().deaths().stream()
              .anyMatch(e -> e.player().id().equals(p.id()) && e.hidden());
      JsonObject you = new JsonObject();
      you.addProperty("hidden", hidden);
      o.add("you", you);
    }
    return Reply.ok(o);
  }

  /** Best first, ranked 1, 2, 2, 4 on ties, with those who hide themselves left out. */
  private static JsonArray board(List<LeaderboardEntry> entries) {
    JsonArray list = new JsonArray();
    int rank = 0;
    int place = 0;
    long last = Long.MIN_VALUE;
    for (LeaderboardEntry e : entries) {
      if (e.hidden()) {
        continue;
      }
      place++;
      if (e.value() != last) {
        rank = place;
        last = e.value();
      }
      JsonObject o = new JsonObject();
      o.addProperty("rank", rank);
      o.addProperty("uuid", e.player().id().toString());
      o.addProperty("name", e.player().name());
      o.addProperty("value", e.value());
      list.add(o);
    }
    return list;
  }

  // Reference

  private Reply waypoints(Viewer viewer) {
    Optional<Reply> denied = deny("waypoints", viewer);
    if (denied.isPresent()) {
      return denied.get();
    }
    Optional<SeasonsApi> seasons = api.get();
    if (seasons.isEmpty()) {
      return unavailable();
    }
    JsonArray list = new JsonArray();
    for (WaypointView w : seasons.get().waypoints()) {
      JsonObject o = new JsonObject();
      o.addProperty("set", w.set());
      o.addProperty("name", w.name());
      o.addProperty("dimension", w.dimension());
      o.addProperty("x", w.x());
      o.addProperty("y", w.y());
      o.addProperty("z", w.z());
      list.add(o);
    }
    JsonObject o = new JsonObject();
    o.add("waypoints", list);
    return Reply.ok(o);
  }

  private Reply links(Viewer viewer) {
    Optional<Reply> denied = deny("links", viewer);
    if (denied.isPresent()) {
      return denied.get();
    }
    JsonArray list = new JsonArray();
    Optional<WebSettings.Link> map = config.map();
    for (WebSettings.Link link : config.links()) {
      // A hidden map is hidden as a link too.
      if (map.isPresent() && link.equals(map.get()) && !sees("map", viewer)) {
        continue;
      }
      JsonObject o = new JsonObject();
      o.addProperty("label", link.label());
      o.addProperty("url", link.url());
      String embed = link.embed() == null ? "" : link.embed().strip();
      if (embed.isEmpty()) {
        o.add("embed", JsonNull.INSTANCE);
      } else {
        o.addProperty("embed", embed);
      }
      list.add(o);
    }
    JsonObject o = new JsonObject();
    o.add("links", list);
    return Reply.ok(o);
  }

  private Reply health(Viewer viewer) {
    Optional<Reply> denied = deny("server-health", viewer);
    if (denied.isPresent()) {
      return denied.get();
    }
    Optional<Health> sampled = health.get();
    if (sampled.isEmpty()) {
      return unavailable();
    }
    Health h = sampled.get();
    JsonObject o = new JsonObject();
    JsonArray tps = new JsonArray();
    for (double v : h.tps()) {
      tps.add(Math.round(v * 100) / 100.0);
    }
    o.add("tps", tps);
    o.addProperty("tickMs", Math.round(h.tickMs() * 100) / 100.0);
    JsonObject memory = new JsonObject();
    memory.addProperty("used", h.usedBytes());
    memory.addProperty("max", h.maxBytes());
    o.add("memory", memory);
    o.addProperty("at", Instant.now(clock).toString());
    return Reply.ok(o);
  }

  // Helpers

  static JsonObject player(PlayerName name) {
    JsonObject o = new JsonObject();
    o.addProperty("uuid", name.id().toString());
    o.addProperty("name", name.name());
    return o;
  }

  private static JsonArray strings(List<String> values) {
    JsonArray list = new JsonArray();
    values.forEach(list::add);
    return list;
  }

  private static JsonArray firsts(List<PlayerProfile.First> firsts) {
    JsonArray list = new JsonArray();
    for (PlayerProfile.First f : firsts) {
      JsonObject o = new JsonObject();
      o.addProperty("key", f.key());
      o.addProperty("label", f.label());
      o.addProperty("at", f.at().toString());
      list.add(o);
    }
    return list;
  }

  private static Optional<UUID> uuid(String text) {
    String t = URLDecoder.decode(text, StandardCharsets.UTF_8);
    if (!t.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
      return Optional.empty();
    }
    return Optional.of(UUID.fromString(t));
  }

  private static Reply unavailable() {
    return Reply.error(503, "unavailable").withHeader("Retry-After", "5");
  }

  /** Waits for Seasons API on this request's own virtual thread; empty if it fails or is slow. */
  private static <T> Optional<T> await(CompletableFuture<T> future) {
    try {
      return Optional.ofNullable(future.get(WAIT_SECONDS, TimeUnit.SECONDS));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (ExecutionException | TimeoutException e) {
      return Optional.empty();
    }
  }
}
