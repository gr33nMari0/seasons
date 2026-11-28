// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;

/**
 * The dashboard's HTTP server: the JDK's own, with a virtual thread per request and no web
 * framework (spec, Security). It applies, in order, the visitor's address (from a trusted proxy's
 * header), the per-visitor and per-account rate limits, the session cookie, the routes, and on
 * every response the security headers and Cache-Control.
 */
final class WebServer implements AutoCloseable {
  /**
   * A keep-alive comment this often, so a proxy's idle timeout (100 s on Cloudflare) never cuts a
   * stream.
   */
  static final Duration KEEP_ALIVE = Duration.ofSeconds(30);

  private final WebConfig config;
  private final Routes routes;
  private final SessionStore sessions;
  private final RateLimiter limiter;
  private final EventHub hub;
  private final Logger log;
  private final HttpServer server;
  private final ExecutorService threads;
  private final String securityPolicy;
  private final SelfCheck selfCheck;

  /**
   * Binds the server; it doesn't answer until {@link #start}.
   *
   * @throws IOException if the address can't be bound, such as a port in use
   */
  WebServer(
      WebConfig config,
      Routes routes,
      SessionStore sessions,
      RateLimiter limiter,
      EventHub hub,
      Logger log,
      SelfCheck selfCheck)
      throws IOException {
    this.selfCheck = selfCheck;
    this.config = config;
    this.routes = routes;
    this.sessions = sessions;
    this.limiter = limiter;
    this.hub = hub;
    this.log = log;
    this.securityPolicy = securityPolicy(config.frameOrigins());
    this.threads =
        Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("SeasonsWeb-http-", 0).factory());
    this.server = HttpServer.create(config.bind(), 64);
    server.createContext("/", this::handle);
    server.setExecutor(threads);
  }

  void start() {
    server.start();
  }

  /** The port it listens on, which a test may have left to the system. */
  int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    hub.closeAll();
    server.stop(0);
    threads.shutdownNow();
  }

  /**
   * The content security policy (spec, Security): nothing but this site's own scripts and styles,
   * skins from Mojang, frames only from the allow-listed sites, and never framed itself.
   */
  static String securityPolicy(List<String> frameOrigins) {
    return "default-src 'self'; script-src 'self'; style-src 'self';"
        + " img-src 'self' https://textures.minecraft.net; connect-src 'self';"
        + " frame-src "
        + (frameOrigins.isEmpty() ? "'none'" : String.join(" ", frameOrigins))
        + "; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'";
  }

  private void handle(HttpExchange exchange) {
    try (exchange) {
      serve(exchange);
    } catch (IOException e) {
      // The visitor went away.
    } catch (RuntimeException e) {
      log.error("Seasons Web failed a request to {}", exchange.getRequestURI().getRawPath(), e);
      try {
        send(exchange, Reply.error(500, "internal"));
      } catch (IOException | RuntimeException ignored) {
        // Already answering.
      }
    }
  }

  private void serve(HttpExchange exchange) throws IOException {
    Headers in = exchange.getRequestHeaders();
    InetAddress peer = exchange.getRemoteAddress().getAddress();
    if (exchange.getRequestURI().getRawPath().equals(Routes.WELL_KNOWN)) {
      String nonce = query(exchange.getRequestURI().getRawQuery()).getOrDefault("check", "");
      if (!nonce.isEmpty()) {
        String header = config.clientIp().header();
        selfCheck.arrived(
            nonce,
            new SelfCheck.Arrival(
                peer,
                !header.isEmpty() && in.getFirst(header) != null,
                config.clientIp().trusts(peer)));
      }
    }
    String ip =
        config.clientIp().key(peer, Optional.ofNullable(in.getFirst(config.clientIp().header())));
    WebSettings.RateLimits limits = config.settings().rateLimits();
    long wait = limiter.take("ip:" + ip, limits.requestsPerMinutePerIp());
    if (wait > 0) {
      send(exchange, limited(wait));
      return;
    }
    Map<String, String> cookies = cookies(in);
    Viewer viewer = Viewer.ANONYMOUS;
    String token = cookies.getOrDefault(Routes.SESSION_COOKIE, "");
    if (!token.isEmpty()) {
      Optional<SessionStore.Session> session = sessions.find(token);
      if (session.isPresent()) {
        SessionStore.Session s = session.get();
        viewer = new Viewer.Player(s.player(), s.name(), s.grants(), s.id());
        wait = limiter.take("account:" + s.player(), limits.requestsPerMinutePerAccount());
        if (wait > 0) {
          send(exchange, limited(wait));
          return;
        }
      }
    }
    String path = exchange.getRequestURI().getRawPath();
    String method = exchange.getRequestMethod();
    if (path.equals(Routes.EVENTS) && method.equals("GET")) {
      stream(exchange, viewer, ip);
      return;
    }
    String length = in.getFirst("Content-Length");
    long bodyLength = 0;
    if (length != null) {
      try {
        bodyLength = Long.parseLong(length.strip());
      } catch (NumberFormatException e) {
        bodyLength = 1;
      }
    } else if (in.containsKey("Transfer-Encoding")) {
      bodyLength = 1;
    }
    Reply reply =
        routes.route(
            new Routes.Request(
                method,
                path,
                query(exchange.getRequestURI().getRawQuery()),
                viewer,
                ip,
                cookies,
                Optional.ofNullable(in.getFirst("User-Agent")).orElse(""),
                bodyLength,
                Optional.ofNullable(in.getFirst("If-None-Match")).orElse("")));
    send(exchange, reply);
  }

  private static Reply limited(long wait) {
    return Reply.error(429, "rate-limited").withHeader("Retry-After", Long.toString(wait));
  }

  private void send(HttpExchange exchange, Reply reply) throws IOException {
    Headers out = exchange.getResponseHeaders();
    headers(out);
    out.set("Content-Type", reply.contentType());
    out.set("Cache-Control", cacheControl(reply.personal() || reply.status() >= 400));
    if (!reply.personal() && reply.status() < 400) {
      out.set("Vary", "Cookie");
    }
    reply.headers().forEach(out::set);
    for (String cookie : reply.cookies()) {
      out.add("Set-Cookie", cookie);
    }
    byte[] body = reply.wire();
    exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
    if (body.length > 0) {
      try (OutputStream o = exchange.getResponseBody()) {
        o.write(body);
      }
    }
  }

  /**
   * Responses that depend on a login, refusals and errors are never cached (spec, Security); public
   * ones may be, for a few seconds.
   */
  String cacheControl(boolean personal) {
    return personal
        ? "private, no-store"
        : "public, max-age=" + config.settings().cache().publicMaxAge().toSeconds();
  }

  private void headers(Headers out) {
    out.set("Content-Security-Policy", securityPolicy);
    out.set("X-Frame-Options", "DENY");
    out.set("X-Content-Type-Options", "nosniff");
    out.set("Referrer-Policy", "no-referrer");
    out.set("Cross-Origin-Opener-Policy", "same-origin");
    out.set("Permissions-Policy", "screen-wake-lock=(self)");
    if (config.publicUrl().isPresent()) {
      out.set("Strict-Transport-Security", "max-age=31536000");
    }
  }

  // The event stream

  private void stream(HttpExchange exchange, Viewer viewer, String ip) throws IOException {
    Optional<Reply> denied = routes.deny("event-feed", viewer);
    if (denied.isPresent()) {
      send(exchange, viewer instanceof Viewer.Player ? denied.get().privately() : denied.get());
      return;
    }
    Optional<EventHub.Client> opened =
        hub.open(
            ip,
            viewer instanceof Viewer.Player p ? Optional.of(p.id()) : Optional.empty(),
            key -> config.visibility().allows(key, viewer));
    if (opened.isEmpty()) {
      send(exchange, Reply.error(429, "too-many-streams").withHeader("Retry-After", "30"));
      return;
    }
    EventHub.Client client = opened.get();
    try {
      Headers out = exchange.getResponseHeaders();
      headers(out);
      out.set("Content-Type", "text/event-stream; charset=utf-8");
      out.set("Cache-Control", viewer instanceof Viewer.Player ? "private, no-store" : "no-store");
      out.set("X-Accel-Buffering", "no");
      exchange.sendResponseHeaders(200, 0);
      OutputStream body = exchange.getResponseBody();
      write(body, ": connected\n\n");
      // Real time, not the clock a scenario moves.
      Instant nextKeepAlive = Instant.now().plus(KEEP_ALIVE);
      while (!client.closed) {
        Duration wait = Duration.between(Instant.now(), nextKeepAlive);
        Optional<String> frame = client.next(wait.isNegative() ? Duration.ZERO : wait);
        if (frame.isPresent() && frame.get().equals(EventHub.CLOSE)) {
          break;
        }
        if (viewer instanceof Viewer.Player p && !sessions.live(p.sessionId())) {
          break;
        }
        if (frame.isPresent()) {
          write(body, frame.get());
        }
        if (!Instant.now().isBefore(nextKeepAlive)) {
          write(body, ": keep-alive\n\n");
          nextKeepAlive = Instant.now().plus(KEEP_ALIVE);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      hub.close(client);
    }
  }

  private static void write(OutputStream body, String text) throws IOException {
    body.write(text.getBytes(StandardCharsets.UTF_8));
    body.flush();
  }

  // Parsing

  static Map<String, String> cookies(Headers in) {
    Map<String, String> out = new HashMap<>();
    List<String> lines = in.get("Cookie");
    if (lines == null) {
      return out;
    }
    for (String line : lines) {
      for (String pair : line.split(";", -1)) {
        int eq = pair.indexOf('=');
        if (eq > 0) {
          out.putIfAbsent(pair.substring(0, eq).strip(), pair.substring(eq + 1).strip());
        }
      }
    }
    return out;
  }

  static Map<String, String> query(String raw) {
    Map<String, String> out = new HashMap<>();
    if (raw == null || raw.isEmpty()) {
      return out;
    }
    for (String pair : raw.split("&", -1)) {
      int eq = pair.indexOf('=');
      String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
      String value =
          eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      out.putIfAbsent(key, value);
    }
    return out;
  }
}
