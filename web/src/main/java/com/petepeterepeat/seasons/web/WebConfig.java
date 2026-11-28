// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * web.yml checked: where to listen, the public address, the proxy, the links and who sees what. The
 * bind rule (spec, Security): localhost always; any other address only with an https public-url.
 */
record WebConfig(
    WebSettings settings,
    InetSocketAddress bind,
    Optional<URI> publicUrl,
    ClientIp clientIp,
    List<WebSettings.Link> links,
    List<String> frameOrigins,
    Visibility visibility,
    Map<String, List<String>> screens,
    List<String> gameAddresses,
    List<WebSettings.Link> helpLinks,
    Optional<String> rulesFile) {
  static final String SPOTIFY = "https://open.spotify.com";

  /**
   * The names a screen may list, each with the widget it shows: the spec's example names ({@code
   * season-clock}, {@code leaderboard}) and every widget's own.
   */
  static final Map<String, String> SCREEN_WIDGETS;

  static {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("season-clock", "season");
    m.put("season", "season");
    m.put("online", "online");
    m.put("leaderboard", "leaderboards");
    m.put("leaderboards", "leaderboards");
    m.put("event-feed", "event-feed");
    m.put("map", "map");
    m.put("links", "links");
    m.put("hall", "hall");
    m.put("museum", "museum");
    m.put("history", "history");
    m.put("rules", "rules");
    m.put("waypoints", "waypoints");
    m.put("converter", "converter");
    m.put("server-health", "server-health");
    m.put("pending-players", "pending-players");
    m.put("audit-log", "audit-log");
    SCREEN_WIDGETS = Collections.unmodifiableMap(m);
  }

  WebConfig {
    links = List.copyOf(links);
    frameOrigins = List.copyOf(frameOrigins);
    Map<String, List<String>> copy = new LinkedHashMap<>();
    screens.forEach((k, v) -> copy.put(k, List.copyOf(v)));
    screens = Collections.unmodifiableMap(copy);
  }

  /** The map: the first link shown in a frame (spec, Widgets), if any. */
  Optional<WebSettings.Link> map() {
    return links.stream()
        .filter(l -> l.embed() != null && l.embed().strip().equalsIgnoreCase("frame"))
        .findFirst();
  }

  Duration sessionLifetime() {
    return settings.sessions().lifetime();
  }

  /** The checked config, or why the dashboard stays off; with any warnings either way. */
  sealed interface Result {
    List<String> warnings();
  }

  /** Good to listen. */
  record Ready(WebConfig config, List<String> warnings) implements Result {}

  /** Not listening, and why, as the reload reply and the log say it. */
  record Off(String reason, List<String> warnings) implements Result {}

  static Result check(WebSettings settings) {
    List<String> warnings = new ArrayList<>();
    if (!settings.enabled()) {
      return new Off("enabled: false", warnings);
    }
    InetSocketAddress bind;
    try {
      bind = bind(settings.bind());
    } catch (IllegalArgumentException e) {
      return new Off("bind: " + e.getMessage(), warnings);
    }
    Optional<URI> publicUrl;
    try {
      publicUrl = publicUrl(settings.publicUrl());
    } catch (IllegalArgumentException e) {
      return new Off("public-url: " + e.getMessage(), warnings);
    }
    if (!bind.getAddress().isLoopbackAddress() && publicUrl.isEmpty()) {
      return new Off(
          "bind "
              + settings.bind()
              + " is beyond localhost, which needs an https public-url in front",
          warnings);
    }
    ClientIp clientIp;
    try {
      clientIp = new ClientIp(settings.proxy().trusted(), settings.proxy().clientIpHeader());
    } catch (IllegalArgumentException e) {
      return new Off("proxy.trusted: " + e.getMessage(), warnings);
    }
    List<WebSettings.Link> links = new ArrayList<>();
    TreeSet<String> frames = new TreeSet<>();
    for (WebSettings.Link link : settings.links()) {
      Optional<String> problem = linkProblem(link);
      if (problem.isPresent()) {
        warnings.add("links: \"" + link.label() + "\" is left out: " + problem.get());
        continue;
      }
      links.add(link);
      String embed = link.embed() == null ? "" : link.embed();
      if (embed.equals("spotify")) {
        frames.add(SPOTIFY);
      } else if (embed.equals("frame")) {
        frames.add(origin(URI.create(link.url())));
      }
    }
    Visibility visibility = new Visibility(settings.visibility(), settings.open(), warnings);
    Map<String, List<String>> screens = screens(settings.screens(), warnings);
    if (settings.sessions().lifetime().compareTo(Duration.ofMinutes(1)) < 0) {
      return new Off("sessions.lifetime must be at least a minute", warnings);
    }
    List<String> gameAddresses = new ArrayList<>();
    for (String typed : settings.gameAddresses()) {
      Optional<String> address = Discovery.normalise(typed);
      if (address.isEmpty()) {
        warnings.add(
            "game-addresses: \""
                + typed
                + "\" is left out: a game address is a host name, with a port if not 25565");
      } else if (!gameAddresses.contains(address.get())) {
        gameAddresses.add(address.get());
      }
    }
    List<WebSettings.Link> helpLinks = new ArrayList<>();
    for (WebSettings.Link link : settings.help().links()) {
      Optional<String> problem = linkProblem(link);
      if (problem.isPresent()) {
        warnings.add("help.links: \"" + link.label() + "\" is left out: " + problem.get());
      } else {
        helpLinks.add(new WebSettings.Link(link.label(), link.url(), null));
      }
    }
    String rules = settings.help().rules().strip();
    Optional<String> rulesFile = Optional.empty();
    if (!rules.isEmpty()) {
      if (rules.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}\\.md")) {
        rulesFile = Optional.of(rules);
      } else {
        warnings.add(
            "help.rules: \""
                + rules
                + "\" is left out: a Markdown file name in plugins/SeasonsWeb/");
      }
    }
    return new Ready(
        new WebConfig(
            settings,
            bind,
            publicUrl,
            clientIp,
            links,
            List.copyOf(frames),
            visibility,
            screens,
            List.copyOf(gameAddresses),
            List.copyOf(helpLinks),
            rulesFile),
        warnings);
  }

  /**
   * Each screen's widgets as their widget keys, in order: a screen with another layout than
   * landscape, and a name that is no widget, are left out with a warning.
   */
  static Map<String, List<String>> screens(
      Map<String, WebSettings.Screen> configured, List<String> warnings) {
    Map<String, List<String>> out = new TreeMap<>();
    configured.forEach((name, screen) -> {
      String key = "screens." + name;
      if (!name.matches("[a-z0-9][a-z0-9-]{0,31}")) {
        warnings.add(
            key + ": a screen's name is lower-case letters, digits and dashes; it is left out");
        return;
      }
      String layout = screen.layout() == null ? "landscape" : screen.layout().strip();
      if (!layout.equalsIgnoreCase("landscape")) {
        warnings.add(key + ".layout: \"" + layout + "\" isn't landscape; the screen is left out");
        return;
      }
      List<String> widgets = new ArrayList<>();
      for (String w : screen.widgets()) {
        String widget = SCREEN_WIDGETS.get(w.strip().toLowerCase(Locale.ROOT));
        if (widget == null) {
          warnings.add(key + ": no widget \"" + w + "\"; it is left out");
        } else if (!widgets.contains(widget)) {
          widgets.add(widget);
        }
      }
      out.put(name, widgets);
    });
    return out;
  }

  /** {@code host:port}, the host an IP address or {@code localhost}, never a name to look up. */
  static InetSocketAddress bind(String text) {
    String t = text.strip();
    int colon = t.lastIndexOf(':');
    if (colon <= 0) {
      throw new IllegalArgumentException(
          "\"" + text + "\" isn't address:port, such as 127.0.0.1:8019");
    }
    String host = t.substring(0, colon);
    if (host.startsWith("[") && host.endsWith("]")) {
      host = host.substring(1, host.length() - 1);
    }
    int port;
    try {
      port = Integer.parseInt(t.substring(colon + 1));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("\"" + text + "\" has no port number", e);
    }
    if (port < 1 || port > 65_535) {
      throw new IllegalArgumentException("port " + port + " isn't between 1 and 65535");
    }
    InetAddress address;
    if (host.equalsIgnoreCase("localhost")) {
      address = InetAddress.getLoopbackAddress();
    } else {
      try {
        address = InetAddress.ofLiteral(host);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "\"" + host + "\" isn't an IP address; use 127.0.0.1, or 0.0.0.0 for every address", e);
      }
    }
    return new InetSocketAddress(address, port);
  }

  /** An https address with nothing after the host, or empty for none. */
  static Optional<URI> publicUrl(String text) {
    String t = text.strip();
    // /web upgrade writes [fill me] where only the admin knows the value: unset until then.
    if (t.isEmpty() || t.equals(SettingsAudit.PLACEHOLDER)) {
      return Optional.empty();
    }
    URI uri;
    try {
      uri = new URI(t.endsWith("/") ? t.substring(0, t.length() - 1) : t);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("\"" + text + "\" isn't an address", e);
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())) {
      throw new IllegalArgumentException("\"" + text + "\" must be https");
    }
    if (uri.getHost() == null
        || uri.getRawUserInfo() != null
        || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null) {
      throw new IllegalArgumentException(
          "\"" + text + "\" must be just https:// and a host, such as https://seasons.example.com");
    }
    return Optional.of(uri);
  }

  private static Optional<String> linkProblem(WebSettings.Link link) {
    if (link.label() == null || link.label().isBlank()) {
      return Optional.of("it has no label");
    }
    URI uri;
    try {
      uri = new URI(link.url());
    } catch (URISyntaxException | NullPointerException e) {
      return Optional.of("\"" + link.url() + "\" isn't an address");
    }
    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
      return Optional.of("its url must be https");
    }
    String embed = link.embed() == null ? "" : link.embed().strip().toLowerCase(Locale.ROOT);
    return switch (embed) {
      case "" -> Optional.empty();
      case "spotify" ->
          uri.getHost().equalsIgnoreCase("open.spotify.com")
              ? Optional.empty()
              : Optional.of("embed: spotify needs an open.spotify.com url");
      case "frame" -> Optional.empty();
      default -> Optional.of("embed is \"" + link.embed() + "\", not spotify or frame");
    };
  }

  static String origin(URI uri) {
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    return "https://"
        + host
        + (uri.getPort() > 0 && uri.getPort() != 443 ? ":" + uri.getPort() : "");
  }

  /** The address as the reload reply and the log write it. */
  String where() {
    String host = bind.getAddress().getHostAddress();
    return (host.contains(":") ? "[" + host + "]" : host) + ":" + bind.getPort();
  }
}
