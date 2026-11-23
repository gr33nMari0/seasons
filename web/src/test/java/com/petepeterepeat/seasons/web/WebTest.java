// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import com.petepeterepeat.seasons.api.event.SeasonsEvent;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Seasons Web over real HTTP on localhost, with a stand-in for Seasons API. */
class WebTest {
  @TempDir Path dir;
  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-23T10:00:00Z"));
  private final UUID alex = UUID.randomUUID();
  private final HttpClient http = HttpClient.newHttpClient();
  private Web web;
  private int port;
  private final FakeApi api = new FakeApi();

  private final class FakeApi implements SeasonsApi {
    Optional<PlayerProfile> profile = Optional.empty();
    boolean savingFails;

    @Override
    public String version() {
      return "1.2.0";
    }

    @Override
    public OptionalInt currentSeason() {
      return OptionalInt.of(3);
    }

    @Override
    public Optional<SeasonView> season() {
      return Optional.of(
          new SeasonView(
              3,
              "active",
              Optional.of("frontier"),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              List.of(new SeasonView.ZoneView("old base", "block")),
              Optional.of(Instant.parse("2026-12-24T08:00:00Z"))));
    }

    @Override
    public List<OnlinePlayer> online() {
      return List.of(
          new OnlinePlayer(
              alex,
              "Alex",
              Optional.empty(),
              "minecraft:overworld",
              new OnlinePlayer.Region("biome", "minecraft:plains"),
              false));
    }

    @Override
    public CompletableFuture<Optional<PlayerProfile>> profile(UUID player) {
      return CompletableFuture.completedFuture(profile);
    }

    @Override
    public CompletableFuture<Optional<GalleryView>> gallery(int season) {
      return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public CompletableFuture<Optional<ArchiveView>> archive(int season, UUID player) {
      return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public CompletableFuture<Leaderboards> leaderboards(int season) {
      LeaderboardEntry e = new LeaderboardEntry(new PlayerName(alex, "Alex"), 3, false);
      return CompletableFuture.completedFuture(
          new Leaderboards(season, List.of(e), List.of(e), List.of(e)));
    }

    @Override
    public List<WaypointView> waypoints() {
      return List.of();
    }

    @Override
    public Subscription subscribe(Consumer<SeasonsEvent> listener) {
      return () -> {};
    }

    @Override
    public CompletableFuture<List<SeasonSummary>> seasons() {
      return CompletableFuture.completedFuture(
          List.of(
              new SeasonSummary(
                  2,
                  false,
                  Optional.of(Instant.parse("2026-09-01T10:00:00Z")),
                  Optional.of(Instant.parse("2026-10-01T10:00:00Z")),
                  4,
                  7,
                  true),
              new SeasonSummary(3, true, Optional.empty(), Optional.empty(), 1, 0, false)));
    }

    @Override
    public CompletableFuture<MuseumView> museum() {
      return CompletableFuture.completedFuture(
          new MuseumView(
              List.of(
                  new MuseumView.Trophy(
                      "museum:core",
                      Optional.of("The first core"),
                      2,
                      "Peter",
                      Instant.parse("2026-09-10T10:00:00Z"),
                      Optional.empty())),
              List.of(
                  new MuseumView.Egg(
                      2, new PlayerName(alex, "Alex"), Instant.parse("2026-09-20T10:00:00Z")))));
    }

    @Override
    public CompletableFuture<List<PendingPlayer>> pending() {
      return CompletableFuture.completedFuture(
          List.of(new PendingPlayer(new PlayerName(alex, "Alex"), 2)));
    }

    @Override
    public CompletableFuture<List<AuditEntry>> audit(int count) {
      return CompletableFuture.completedFuture(
          List.of(
              new AuditEntry(
                  Instant.parse("2026-11-23T09:00:00Z"), "Peter", "zone-add", "old-base", "ok")));
    }

    @Override
    public CompletableFuture<Void> setLeaderboardHidden(UUID player, boolean hidden) {
      return savingFails
          ? CompletableFuture.failedFuture(new IllegalStateException("disk full"))
          : CompletableFuture.completedFuture(null);
    }
  }

  @BeforeEach
  void start() throws Exception {
    try (ServerSocket free = new ServerSocket(0)) {
      port = free.getLocalPort();
    }
    Path folder = dir.resolve("plugin");
    Files.createDirectories(folder);
    Files.writeString(
        folder.resolve("web.yml"),
        "enabled: true\nbind: 127.0.0.1:"
            + port
            + "\nrate-limits:\n  requests-per-minute-per-ip: 30\n");
    web =
        new Web(
            folder,
            dir.resolve("data"),
            LoggerFactory.getLogger("test"),
            clock,
            () -> Optional.of(api),
            Optional::empty,
            () -> "Test server",
            "1.2.0");
    web.start();
    assertThat(web.reload()).isEqualTo("listening on 127.0.0.1:" + port);
  }

  @AfterEach
  void stop() {
    web.close();
  }

  private HttpResponse<String> get(String path, String cookie) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    if (!cookie.isEmpty()) {
      b.header("Cookie", cookie);
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void publicResponsesMayBeCachedBrieflyAndCarryTheSecurityHeaders() throws Exception {
    HttpResponse<String> season = get("/api/v1/season", "");
    assertThat(season.statusCode()).isEqualTo(200);
    assertThat(season.headers().firstValue("Cache-Control")).contains("public, max-age=5");
    assertThat(season.headers().firstValue("Content-Security-Policy").orElseThrow())
        .contains("frame-ancestors 'none'");
    assertThat(season.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
    HttpResponse<String> boards = get("/api/v1/leaderboards", "");
    assertThat(boards.statusCode()).isEqualTo(401);
    assertThat(boards.headers().firstValue("Cache-Control")).contains("private, no-store");
  }

  @Test
  void theDeviceCodeLoginEndToEnd() throws Exception {
    HttpResponse<String> asked = post("/api/v1/auth/device");
    assertThat(asked.statusCode()).isEqualTo(200);
    String code = JsonParser.parseString(asked.body()).getAsJsonObject().get("code").getAsString();
    String device = asked.headers().firstValue("Set-Cookie").orElseThrow().split(";", -1)[0];
    WebCommands commands = new WebCommands(web, Optional::empty, clock);
    assertThat(commands.link(alex, "Alex", false, code))
        .extracting(Line::key)
        .containsExactly("link.linked");
    HttpResponse<String> polled = get("/api/v1/auth/poll", device);
    assertThat(polled.statusCode()).isEqualTo(200);
    String session =
        polled.headers().allValues("Set-Cookie").stream()
            .filter(c -> c.startsWith(Routes.SESSION_COOKIE))
            .findFirst()
            .orElseThrow()
            .split(";", -1)[0];
    assertThat(polled.body()).doesNotContain(session.substring(session.indexOf('=') + 1));
    HttpResponse<String> boards = get("/api/v1/leaderboards", session);
    assertThat(boards.statusCode()).isEqualTo(200);
    assertThat(boards.headers().firstValue("Cache-Control")).contains("private, no-store");
    JsonObject you = JsonParser.parseString(boards.body()).getAsJsonObject().getAsJsonObject("you");
    assertThat(you.get("hidden").getAsBoolean()).isFalse();
    assertThat(commands.sessions(alex)).extracting(Line::key).contains("sessions.header");
    assertThat(commands.logout(alex, "Alex", Optional.of("all")))
        .extracting(Line::key)
        .containsExactly("logout.all");
    assertThat(get("/api/v1/leaderboards", session).statusCode()).isEqualTo(401);
  }

  /** /web privacy's answers are lines from messages.yml, never an exception's own words. */
  @Test
  void privacyAnswersFromMessagesYml() throws Exception {
    WebCommands commands = new WebCommands(web, () -> Optional.of(api), clock);
    assertThat(commands.privacy(alex).get())
        .as("no record of Alex yet")
        .containsExactly(Line.of("privacy.no-record"));
    api.profile = Optional.of(new PlayerProfile(alex, "Alex", Optional.empty(), List.of(), false));
    assertThat(commands.privacy(alex).get()).containsExactly(Line.of("privacy.hidden"));
    api.savingFails = true;
    assertThat(commands.privacy(alex).get())
        .as("a failure says where to look, not what the exception said")
        .containsExactly(Line.of("privacy.failed"));
    assertThat(new WebCommands(web, Optional::empty, clock).privacy(alex).get())
        .containsExactly(Line.of("privacy.unavailable"));
  }

  @Test
  void wrongCodesAreLimitedPerAccount() {
    WebCommands commands = new WebCommands(web, Optional::empty, clock);
    for (int i = 0; i < 5; i++) {
      assertThat(commands.link(alex, "Alex", false, "ZZZZ-ZZZZ"))
          .extracting(Line::key)
          .containsExactly("link.invalid");
    }
    assertThat(commands.link(alex, "Alex", false, "ZZZZ-ZZZZ"))
        .extracting(Line::key)
        .containsExactly("link.locked");
  }

  @Test
  void perVisitorRateLimit() throws Exception {
    int limited = 0;
    for (int i = 0; i < 31; i++) {
      HttpResponse<String> r = get("/api/v1/season", "");
      if (r.statusCode() == 429) {
        limited++;
        assertThat(r.headers().firstValue("Retry-After")).isPresent();
      }
    }
    assertThat(limited).isEqualTo(1);
  }

  @Test
  void unknownAndWrongMethods() throws Exception {
    assertThat(get("/api/v1/nothing", "").statusCode()).isEqualTo(404);
    assertThat(post("/api/v1/season").statusCode()).isEqualTo(405);
    assertThat(get("/api/v1/players/not-a-uuid", "").statusCode()).isEqualTo(404);
  }

  private String login() throws Exception {
    HttpResponse<String> asked = post("/api/v1/auth/device");
    String code = JsonParser.parseString(asked.body()).getAsJsonObject().get("code").getAsString();
    String device = asked.headers().firstValue("Set-Cookie").orElseThrow().split(";", -1)[0];
    var _ = new WebCommands(web, Optional::empty, clock).link(alex, "Alex", false, code);
    return get("/api/v1/auth/poll", device).headers().allValues("Set-Cookie").stream()
        .filter(c -> c.startsWith(Routes.SESSION_COOKIE))
        .findFirst()
        .orElseThrow()
        .split(";", -1)[0];
  }

  @Test
  void thePageSettingsSayWhatThisViewerMaySee() throws Exception {
    HttpResponse<String> anon = get("/api/v1/dashboard", "");
    assertThat(anon.statusCode()).isEqualTo(200);
    JsonObject a = JsonParser.parseString(anon.body()).getAsJsonObject();
    assertThat(a.get("name").getAsString()).isEqualTo("Test server");
    assertThat(a.getAsJsonObject("viewer").get("kind").getAsString()).isEqualTo("anonymous");
    List<String> widgets =
        a.getAsJsonArray("widgets").asList().stream().map(e -> e.getAsString()).toList();
    assertThat(widgets)
        .contains(
            "season", "online", "event-feed", "hall", "museum", "history", "converter", "rules")
        .doesNotContain("leaderboards", "server-health", "pending-players", "audit-log");
    assertThat(a.getAsJsonObject("fields").get("hall.gear").getAsBoolean()).isFalse();
    assertThat(a.get("map").isJsonNull()).as("no framed link, no map").isTrue();
    assertThat(a.getAsJsonObject("screens").getAsJsonArray("second-screen").size()).isEqualTo(6);
    assertThat(a.getAsJsonObject("texts").get("feed.join").getAsString())
        .isEqualTo("<name> joined");
    assertThat(anon.headers().firstValue("Cache-Control")).contains("public, max-age=5");

    String session = login();
    HttpResponse<String> logged = get("/api/v1/dashboard", session);
    JsonObject p = JsonParser.parseString(logged.body()).getAsJsonObject();
    assertThat(p.getAsJsonObject("viewer").get("name").getAsString()).isEqualTo("Alex");
    assertThat(p.getAsJsonArray("widgets").toString()).contains("leaderboards");
    assertThat(p.getAsJsonObject("fields").get("hall.gear").getAsBoolean()).isTrue();
    assertThat(logged.headers().firstValue("Cache-Control")).contains("private, no-store");
  }

  @Test
  void historyAndTheMuseumArePublicAndTheAdminListsAreForAdmins() throws Exception {
    HttpResponse<String> seasons = get("/api/v1/seasons", "");
    assertThat(seasons.statusCode()).isEqualTo(200);
    assertThat(seasons.body()).contains("\"players\":4").contains("\"current\":true");
    HttpResponse<String> museum = get("/api/v1/museum", "");
    assertThat(museum.statusCode()).isEqualTo(200);
    assertThat(museum.body()).contains("The first core").contains("\"slayer\"");
    assertThat(get("/api/v1/admin/pending", "").statusCode()).isEqualTo(401);
    assertThat(get("/api/v1/admin/audit", "").statusCode()).isEqualTo(401);
    String session = login();
    assertThat(get("/api/v1/admin/pending", session).statusCode()).isEqualTo(403);
    web.sessions().setAdmin(alex, true);
    HttpResponse<String> pending = get("/api/v1/admin/pending", session);
    assertThat(pending.statusCode()).isEqualTo(200);
    assertThat(pending.body()).contains("\"season\":2").contains("Alex");
    HttpResponse<String> audit = get("/api/v1/admin/audit", session);
    assertThat(audit.body()).contains("zone-add").doesNotContain("details");
  }

  @Test
  void thePageAndItsFilesAreTheSameForEveryoneAndCachedByEtag() throws Exception {
    HttpResponse<String> page = get("/hall/2", "");
    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.headers().firstValue("Content-Type")).contains("text/html; charset=utf-8");
    assertThat(page.body()).contains("/app/app.mjs");
    String session = login();
    HttpResponse<String> app = get("/app/app.mjs", session);
    assertThat(app.statusCode()).isEqualTo(200);
    assertThat(app.headers().firstValue("Cache-Control")).contains("no-cache");
    assertThat(app.headers().firstValue("Content-Security-Policy").orElseThrow())
        .contains("script-src 'self'");
    String etag = app.headers().firstValue("ETag").orElseThrow();
    HttpResponse<String> again =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/app/app.mjs"))
                .header("If-None-Match", etag)
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(again.statusCode()).isEqualTo(304);
    assertThat(post("/app/app.mjs").statusCode()).isEqualTo(405);
    assertThat(get("/app/../web.yml", "").statusCode()).isEqualTo(404);
  }

  @Test
  void theSeasonSaysWhenHomecomingIsPlannedAndItsZonesByName() throws Exception {
    assertThat(get("/api/v1/season", "").body())
        .contains("\"plannedHomecoming\":\"2026-12-24T08:00:00Z\"")
        .contains("\"name\":\"old base\",\"mode\":\"block\"");
  }
}
