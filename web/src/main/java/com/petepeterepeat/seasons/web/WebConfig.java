// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
    Visibility visibility) {
  static final String SPOTIFY = "https://open.spotify.com";

  WebConfig {
    links = List.copyOf(links);
    frameOrigins = List.copyOf(frameOrigins);
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
    if (settings.sessions().lifetime().compareTo(Duration.ofMinutes(1)) < 0) {
      return new Off("sessions.lifetime must be at least a minute", warnings);
    }
    return new Ready(
        new WebConfig(settings, bind, publicUrl, clientIp, links, List.copyOf(frames), visibility),
        warnings);
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
    if (t.isEmpty()) {
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
