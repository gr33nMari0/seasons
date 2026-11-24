// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import com.petepeterepeat.seasons.api.GalleryView;
import com.petepeterepeat.seasons.api.MuseumView;
import com.petepeterepeat.seasons.api.PlayerProfile;
import com.petepeterepeat.seasons.api.SeasonView;
import com.petepeterepeat.seasons.api.SeasonsApi;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * Rich link previews for the dashboard's pages (M9; questions 337): the Open Graph and Twitter tags
 * each page's HTML carries, and the picture they point to. Everything shown is what a visitor who
 * isn't logged in may see under web.yml's visibility: a player's preview names them only if
 * profiles' summaries are public and they aren't hidden with /web privacy. Pictures are drawn on
 * demand, off the server thread, cached by what they show (their address carries its hash), and at
 * most {@link #RENDERS_PER_MINUTE} new ones a minute are drawn, so nobody can make the server draw
 * without end.
 */
final class Previews {
  static final int RENDERS_PER_MINUTE = 20;
  private static final Duration WAIT = Duration.ofSeconds(3);
  private static final Pattern IMAGE =
      Pattern.compile(
          "/og/(server|player|hall|museum|help|history)/([0-9a-fA-F-]{0,36})-([0-9a-f]{12})\\.png");
  private static final Pattern PLAYER = Pattern.compile("/player/([0-9a-fA-F-]{36})");
  private static final Pattern HALL = Pattern.compile("/hall(?:/(\\d{1,4}))?");

  /** One page's preview: what it says, and the picture's address. */
  record Page(
      String title,
      String description,
      String image,
      PreviewCards.Card card,
      Optional<String> skin) {}

  private final WebConfig config;
  private final Supplier<Optional<SeasonsApi>> api;
  private final Supplier<String> serverName;
  private final Supplier<Messages> messages;
  private final Predicate<String> publicly;
  private final Clock clock;
  private final PreviewCards cards;
  private final HttpClient http;
  private final Map<String, Blob> drawn = lru(64);
  private final Map<String, Optional<BufferedImage>> faces = lru(256);
  private final ArrayList<Instant> renders = new ArrayList<>();

  /**
   * @param publicly whether a visitor who isn't logged in may see a widget or field
   */
  Previews(
      WebConfig config,
      Supplier<Optional<SeasonsApi>> api,
      Supplier<String> serverName,
      Supplier<Messages> messages,
      Predicate<String> publicly,
      Clock clock,
      PreviewCards cards,
      HttpClient http) {
    this.config = config;
    this.api = api;
    this.serverName = serverName;
    this.messages = messages;
    this.publicly = publicly;
    this.clock = clock;
    this.cards = cards;
    this.http = http;
  }

  private static <K, V> Map<K, V> lru(int max) {
    return java.util.Collections.synchronizedMap(
        new LinkedHashMap<K, V>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > max;
          }
        });
  }

  boolean on() {
    return config.settings().previews().enabled() && config.publicUrl().isPresent();
  }

  private String t(String key, String... pairs) {
    return messages.get().text("previews." + key, pairs);
  }

  private static <T> Optional<T> await(CompletableFuture<Optional<T>> future) {
    try {
      return future.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  // What each page says

  /** The preview of a page address, or empty for an address that has none. */
  Optional<Page> page(String path) {
    if (!on()) {
      return Optional.empty();
    }
    Matcher player = PLAYER.matcher(path);
    if (player.matches()) {
      return Optional.of(player(UUID.fromString(player.group(1))));
    }
    Matcher hall = HALL.matcher(path);
    if (hall.matches()) {
      return Optional.of(hall(hall.group(1) == null ? 0 : Integer.parseInt(hall.group(1))));
    }
    if (path.equals("/museum")) {
      return Optional.of(museum());
    }
    if (path.equals("/help") || path.startsWith("/help/")) {
      return Optional.of(
          simple(
              "help", t("help-title"), t("help-description", "server", serverName.get()), "chest"));
    }
    if (path.equals("/history")) {
      return Optional.of(
          simple(
              "history",
              t("history-title"),
              t("history-description", "server", serverName.get()),
              "hourglass"));
    }
    return Optional.of(server());
  }

  private Optional<SeasonView> season() {
    return publicly.test("season") ? api.get().flatMap(SeasonsApi::season) : Optional.empty();
  }

  private Page server() {
    Optional<SeasonView> s = season();
    List<String> bits = new ArrayList<>();
    s.ifPresent(v -> {
      bits.add(stage(v));
      v.launchedAt()
          .ifPresent(l -> {
            long day = Duration.between(l, clock.instant()).toDays() + 1;
            if (day > 0) {
              bits.add(t("day", "day", Long.toString(day)));
            }
          });
    });
    if (publicly.test("online")) {
      int n = api.get().map(a -> a.online().size()).orElse(0);
      bits.add(
          n == 0
              ? t("online-none")
              : n == 1 ? t("online-one") : t("online", "count", Integer.toString(n)));
    }
    String title =
        s.map(v -> t("season", "season", Integer.toString(v.season()))).orElse(serverName.get());
    String description = bits.isEmpty() ? t("server-description") : String.join(" · ", bits);
    return make(
        "server",
        "",
        title,
        description,
        "",
        new PreviewCards.Art("figure-diamond"),
        Optional.empty());
  }

  private String stage(SeasonView v) {
    return switch (v.phase()) {
      case "active" -> t("stage-" + v.stage().orElse("open"));
      default -> t("phase-" + v.phase());
    };
  }

  private Page player(UUID id) {
    Optional<PlayerProfile> profile =
        publicly.test("profiles.summary")
            ? api.get().flatMap(a -> await(a.profile(id)))
            : Optional.empty();
    if (profile.isEmpty() || profile.get().leaderboardHidden()) {
      return make(
          "player",
          id.toString(),
          t("player-hidden-title", "server", serverName.get()),
          t("player-hidden"),
          t("player-section"),
          new PreviewCards.Art("figure-iron"),
          Optional.empty());
    }
    PlayerProfile p = profile.get();
    int seasons = p.seasons().size();
    int awards = p.seasons().stream().mapToInt(s -> s.awards().size()).sum();
    List<String> bits = new ArrayList<>();
    bits.add(
        seasons == 1
            ? t("player-season-one")
            : t("player-seasons", "count", Integer.toString(seasons)));
    if (awards > 0) {
      bits.add(
          awards == 1
              ? t("player-award-one")
              : t("player-awards", "count", Integer.toString(awards)));
    }
    p.seasons().stream()
        .flatMap(s -> s.firsts().stream())
        .findFirst()
        .ifPresent(f -> bits.add(t("player-first", "label", f.label())));
    Optional<String> skin =
        config.settings().previews().skins()
            ? p.skin().filter(u -> u.startsWith("https://textures.minecraft.net/"))
            : Optional.empty();
    return make(
        "player",
        id.toString(),
        p.name(),
        String.join(" · ", bits),
        t("player-section"),
        new PreviewCards.Art("figure-diamond"),
        skin);
  }

  /** A gallery's preview; 0 is the newest finished season's. */
  private Page hall(int which) {
    int season = which;
    if (season == 0) {
      season = season().map(SeasonView::season).map(n -> Math.max(1, n - 1)).orElse(1);
    }
    int number = season;
    Optional<GalleryView> gallery =
        publicly.test("hall") ? api.get().flatMap(a -> await(a.gallery(number))) : Optional.empty();
    String description =
        gallery
            .map(g -> t("hall-cells", "count", Integer.toString(g.cells().size())))
            .orElse(t("hall-description"));
    String[] armour = {"figure-diamond", "figure-gold", "figure-iron", "figure-netherite"};
    return make(
        "hall",
        Integer.toString(number),
        t("hall-title", "season", Integer.toString(number)),
        description,
        t("hall-section"),
        new PreviewCards.Art(armour[Math.floorMod(number - 1, armour.length)]),
        Optional.empty());
  }

  private Page museum() {
    Optional<MuseumView> m =
        publicly.test("museum")
            ? api.get().flatMap(a -> await(a.museum().thenApply(Optional::of)))
            : Optional.empty();
    int eggs = m.map(v -> v.eggs().size()).orElse(0);
    String description =
        eggs == 0
            ? t("museum-description")
            : eggs == 1 ? t("museum-egg-one") : t("museum-eggs", "count", Integer.toString(eggs));
    return make(
        "museum",
        "",
        t("museum-title"),
        description,
        t("museum-section"),
        new PreviewCards.Art("egg"),
        Optional.empty());
  }

  private Page simple(String kind, String title, String description, String art) {
    return make(kind, "", title, description, "", new PreviewCards.Art(art), Optional.empty());
  }

  private Page make(
      String kind,
      String key,
      String title,
      String description,
      String section,
      PreviewCards.Art art,
      Optional<String> skin) {
    PreviewCards.Card card = new PreviewCards.Card(section, title, description, art);
    String hash =
        hash(
            serverName.get()
                + "\n"
                + kind
                + "\n"
                + key
                + "\n"
                + section
                + "\n"
                + title
                + "\n"
                + description
                + "\n"
                + art.name()
                + "\n"
                + skin.orElse(""));
    return new Page(
        title, description, "/og/" + kind + "/" + key + "-" + hash + ".png", card, skin);
  }

  private static String hash(String text) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(d, 0, 6);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  // The tags

  /** The page's head: its title and, with previews on, every tag a shared link unfurls with. */
  String head(String path) {
    String server = serverName.get();
    Optional<Page> found = page(path);
    if (found.isEmpty()) {
      return "<title>" + escape(server) + "</title>";
    }
    Page p = found.get();
    String base = config.publicUrl().map(WebConfig::origin).orElse("");
    String url = base + path;
    String image = base + p.image();
    String title =
        p.title().contains(server)
            ? p.title()
            : t("meta-title", "title", p.title(), "server", server);
    StringBuilder out = new StringBuilder();
    out.append("<title>").append(escape(title)).append("</title>\n");
    meta(out, "name", "description", p.description());
    meta(out, "property", "og:type", "website");
    meta(out, "property", "og:site_name", server);
    meta(out, "property", "og:title", title);
    meta(out, "property", "og:description", p.description());
    meta(out, "property", "og:url", url);
    meta(out, "property", "og:image", image);
    meta(out, "property", "og:image:width", Integer.toString(PreviewCards.WIDTH));
    meta(out, "property", "og:image:height", Integer.toString(PreviewCards.HEIGHT));
    meta(out, "property", "og:image:alt", title);
    meta(out, "name", "twitter:card", "summary_large_image");
    meta(out, "name", "twitter:title", title);
    meta(out, "name", "twitter:description", p.description());
    meta(out, "name", "twitter:image", image);
    meta(out, "name", "theme-color", "#1d2333");
    out.append("<link rel=\"canonical\" href=\"").append(escape(url)).append("\">");
    return out.toString();
  }

  private static void meta(StringBuilder out, String attr, String name, String content) {
    out.append("<meta ")
        .append(attr)
        .append("=\"")
        .append(name)
        .append("\" content=\"")
        .append(escape(content))
        .append("\">\n");
  }

  static String escape(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  // The pictures

  /** The picture at an /og/ address: drawn now, or from the cache; empty for any other address. */
  Optional<Reply> image(String path) {
    Matcher m = IMAGE.matcher(path);
    if (!m.matches() || !on()) {
      return Optional.empty();
    }
    String kind = m.group(1);
    String key = m.group(2);
    String page =
        switch (kind) {
          case "player" -> key.length() == 36 ? "/player/" + key : "";
          case "hall" -> key.isEmpty() ? "/hall" : "/hall/" + key;
          case "museum" -> "/museum";
          case "help" -> "/help";
          case "history" -> "/history";
          default -> "/";
        };
    if (page.isEmpty()) {
      return Optional.empty();
    }
    Optional<Page> found = page(page);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    Page p = found.get();
    Blob png = drawn.get(p.image());
    if (png == null) {
      if (!mayRender()) {
        return Optional.of(Reply.error(503, "previews-busy").withHeader("Retry-After", "30"));
      }
      PreviewCards.Card card = p.card();
      Optional<BufferedImage> face = p.skin().flatMap(this::face);
      if (face.isPresent()) {
        card =
            new PreviewCards.Card(
                card.section(),
                card.title(),
                card.description(),
                new PreviewCards.Face(face.get()));
      }
      String host = config.publicUrl().map(URI::getHost).orElse("");
      png = cards.draw(serverName.get(), host, card);
      drawn.put(p.image(), png);
    }
    // The address carries a hash of what is shown: a changed page gets a new address.
    boolean current = p.image().equals(path);
    return Optional.of(
        Reply.binary("image/png", png)
            .withHeader(
                "Cache-Control",
                current ? "public, max-age=86400, immutable" : "public, max-age=300"));
  }

  private synchronized boolean mayRender() {
    Instant now = clock.instant();
    renders.removeIf(t -> t.isBefore(now.minus(Duration.ofMinutes(1))));
    if (renders.size() >= RENDERS_PER_MINUTE) {
      return false;
    }
    renders.add(now);
    return true;
  }

  /** A player's face from their skin, fetched once from Mojang's texture server. */
  private Optional<BufferedImage> face(String url) {
    Optional<BufferedImage> cached = faces.get(url);
    if (cached != null) {
      return cached;
    }
    Optional<BufferedImage> got;
    try {
      HttpResponse<byte[]> answer =
          http.send(
              HttpRequest.newBuilder(URI.create(url)).timeout(WAIT).GET().build(),
              HttpResponse.BodyHandlers.ofByteArray());
      BufferedImage skin =
          answer.statusCode() == 200 && answer.body().length < 64 * 1024
              ? ImageIO.read(new ByteArrayInputStream(answer.body()))
              : null;
      got = skin == null ? Optional.empty() : PreviewCards.face(skin);
    } catch (IOException e) {
      got = Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      got = Optional.empty();
    }
    faces.put(url, got);
    return got;
  }
}
