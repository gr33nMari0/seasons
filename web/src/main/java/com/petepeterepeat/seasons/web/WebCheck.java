// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * {@code /web check} (M10 plan, O2): web.yml checked without applying it, then how the dashboard is
 * reached, each line passed, a warning, a problem or skipped, with what to do. The network checks
 * run off the server thread with short timeouts, and only ask for what the dashboard's own settings
 * name: its public-url, its game addresses' DNS records and its map.
 */
final class WebCheck {
  /** How long the check waits for its own request to arrive. */
  static final Duration ARRIVAL = Duration.ofSeconds(6);

  /** A line's verdict. */
  enum Status {
    PASS,
    WARN,
    FAIL,
    SKIP;

    String key() {
      return "check." + name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  /** What fetching a page found: a status and body, or why nothing answered. */
  record Fetched(int status, String body, Optional<String> failed) {}

  /** The network, as the check needs it. */
  interface Network {
    /** A GET of an https address, never following redirects. */
    CompletableFuture<Fetched> fetch(String url);

    /** A name's DNS records of one type (1 A, 28 AAAA, 33 SRV), as their data. */
    CompletableFuture<List<String>> records(String name, int type);
  }

  /** What the check reads of the running dashboard. */
  record State(
      String written,
      String bundled,
      WebSettings settings,
      WebConfig.Result result,
      boolean serving,
      Framing.State map,
      SelfCheck selfCheck,
      Messages messages) {}

  private WebCheck() {}

  /** The whole report: a header, the lines, and a count of each verdict. */
  static CompletableFuture<List<Line>> run(
      State s, Network net, Discovery.Resolver resolver, Discovery.Asker asker) {
    List<Line> lines = new ArrayList<>();
    lines.add(Line.of("check.header"));
    settings(s, lines);
    Optional<WebConfig> config =
        s.result() instanceof WebConfig.Ready ready
            ? Optional.of(ready.config())
            : Optional.empty();
    CompletableFuture<List<Line>> reach = reach(s, config, net);
    CompletableFuture<List<Line>> discovery = discovery(s, config, net, resolver, asker);
    List<Line> mapLines = map(s, config);
    return reach.thenCombine(
        discovery,
        (a, b) -> {
          lines.addAll(a);
          lines.addAll(b);
          lines.addAll(mapLines);
          lines.add(summary(s.messages(), lines));
          return List.copyOf(lines);
        });
  }

  private static Line line(Messages m, Status status, String area, String text, String... pairs) {
    return Line.of(
        status.key(),
        "area",
        m.text("check.area." + area),
        "text",
        m.text("check.text." + text, pairs));
  }

  // Settings

  private static void settings(State s, List<Line> lines) {
    Messages m = s.messages();
    int before = lines.size();
    try {
      ConfigurationNode written = YamlConfigurationLoader.builder().buildAndLoadString(s.written());
      ConfigurationNode bundled = YamlConfigurationLoader.builder().buildAndLoadString(s.bundled());
      for (SettingsAudit.Unknown u : SettingsAudit.unknown(written, bundled)) {
        lines.add(
            u.meant().isPresent()
                ? line(
                    m,
                    Status.WARN,
                    "settings",
                    "settings.unknown",
                    "path",
                    u.path(),
                    "meant",
                    u.meant().get())
                : line(m, Status.WARN, "settings", "settings.unknown-plain", "path", u.path()));
      }
      int missing = SettingsAudit.missing(written, bundled).size();
      if (missing > 0) {
        lines.add(
            line(
                m,
                Status.WARN,
                "settings",
                "settings.missing",
                "count",
                Integer.toString(missing),
                "settings",
                m.text(missing == 1 ? "check.word.setting" : "check.word.settings")));
      }
      for (String path : SettingsAudit.placeholders(written)) {
        lines.add(line(m, Status.WARN, "settings", "settings.placeholder", "path", path));
      }
    } catch (ConfigurateException e) {
      lines.add(
          line(
              m, Status.FAIL, "settings", "settings.unreadable", "why", firstLine(e.getMessage())));
      return;
    }
    for (String w : s.result().warnings()) {
      lines.add(line(m, Status.WARN, "settings", "settings.warning", "warning", w));
    }
    if (s.result() instanceof WebConfig.Off off) {
      lines.add(
          line(
              m,
              s.settings().enabled() ? Status.FAIL : Status.WARN,
              "settings",
              "settings.off",
              "why",
              off.reason()));
    }
    if (lines.size() == before) {
      lines.add(line(m, Status.PASS, "settings", "settings.ok"));
    }
  }

  // The public address and the proxy

  private static CompletableFuture<List<Line>> reach(
      State s, Optional<WebConfig> config, Network net) {
    Messages m = s.messages();
    if (config.isEmpty() || !s.serving()) {
      return done(
          line(m, Status.SKIP, "address", "address.off"),
          line(m, Status.SKIP, "proxy", "proxy.none"));
    }
    WebConfig c = config.get();
    if (c.publicUrl().isEmpty()) {
      return done(
          line(m, Status.WARN, "address", "address.none"),
          line(m, Status.SKIP, "proxy", "proxy.none"));
    }
    String origin = Discovery.origin(c.publicUrl().get());
    String nonce = Tokens.secret().substring(0, 16).replaceAll("[^A-Za-z0-9]", "x");
    CompletableFuture<SelfCheck.Arrival> arrival =
        s.selfCheck().expect(nonce).orTimeout(ARRIVAL.toSeconds(), TimeUnit.SECONDS);
    return net.fetch(origin + Routes.WELL_KNOWN + "?check=" + nonce)
        .thenCompose(fetched -> {
          if (fetched.failed().isPresent()) {
            arrival.cancel(false);
            return done(
                line(
                    m,
                    Status.FAIL,
                    "address",
                    "address.down",
                    "url",
                    origin,
                    "why",
                    fetched.failed().get()),
                line(m, Status.SKIP, "proxy", "proxy.unseen"));
          }
          boolean seasons =
              fetched.status() == 200 && fetched.body().contains("\"service\":\"seasons\"");
          return arrival
              .handle((a, e) -> Optional.ofNullable(e == null ? a : null))
              .thenApply(seen -> {
                Line address;
                if (seen.isPresent()) {
                  address = line(m, Status.PASS, "address", "address.ok", "url", origin);
                } else if (seasons) {
                  address = line(m, Status.FAIL, "address", "address.other", "url", origin);
                } else {
                  address =
                      line(
                          m,
                          Status.FAIL,
                          "address",
                          "address.not-seasons",
                          "url",
                          origin,
                          "status",
                          Integer.toString(fetched.status()));
                }
                return List.of(address, proxy(m, c, seen));
              });
        });
  }

  private static Line proxy(Messages m, WebConfig c, Optional<SelfCheck.Arrival> seen) {
    if (seen.isEmpty()) {
      return line(m, Status.SKIP, "proxy", "proxy.unseen");
    }
    SelfCheck.Arrival a = seen.get();
    String from = a.from().getHostAddress();
    String header = c.clientIp().header().isEmpty() ? "-" : c.clientIp().header();
    if (a.header() && a.trusted()) {
      return line(m, Status.PASS, "proxy", "proxy.ok", "from", from, "header", header);
    }
    if (a.header()) {
      return line(m, Status.WARN, "proxy", "proxy.untrusted", "from", from, "header", header);
    }
    if (a.from().isLoopbackAddress() || isPrivate(a.from())) {
      return line(m, Status.WARN, "proxy", "proxy.no-header", "from", from, "header", header);
    }
    return line(m, Status.PASS, "proxy", "proxy.direct", "from", from);
  }

  private static boolean isPrivate(InetAddress address) {
    return address.isSiteLocalAddress() || address.isLinkLocalAddress();
  }

  // Finding the dashboard from the game address, and joining with it

  private static CompletableFuture<List<Line>> discovery(
      State s,
      Optional<WebConfig> config,
      Network net,
      Discovery.Resolver resolver,
      Discovery.Asker asker) {
    Messages m = s.messages();
    if (config.isEmpty()) {
      return done();
    }
    WebConfig c = config.get();
    if (c.gameAddresses().isEmpty()) {
      return done(line(m, Status.WARN, "discovery", "discovery.none"));
    }
    CompletableFuture<List<Line>> found =
        c.publicUrl().isEmpty()
            ? done(line(m, Status.SKIP, "discovery", "discovery.no-url"))
            : Discovery.check(c.gameAddresses(), c.publicUrl(), resolver, asker)
                .thenApply(each ->
                    each.stream()
                        .map(l ->
                            Line.of(
                                (l.key().equals("discovery.ok") ? Status.PASS : Status.WARN).key(),
                                "area",
                                m.text("check.area.discovery"),
                                "text",
                                plain(m, l)))
                        .toList());
    List<CompletableFuture<Line>> dns = new ArrayList<>();
    List<String> hosts = new ArrayList<>();
    for (String address : c.gameAddresses()) {
      String host = Discovery.host(address);
      if (!hosts.contains(host)) {
        hosts.add(host);
        dns.add(joinable(m, net, host));
      }
    }
    CompletableFuture<List<Line>> records =
        CompletableFuture.allOf(dns.toArray(CompletableFuture[]::new))
            .thenApply(v -> dns.stream().map(CompletableFuture::join).toList());
    return found.thenCombine(
        records,
        (a, b) -> {
          List<Line> out = new ArrayList<>(a);
          out.addAll(b);
          return out;
        });
  }

  /** Whether players can join with an address: an SRV record, or an A or AAAA record. */
  private static CompletableFuture<Line> joinable(Messages m, Network net, String host) {
    CompletableFuture<List<String>> srv = net.records("_minecraft._tcp." + host, 33);
    CompletableFuture<List<String>> a = net.records(host, 1);
    CompletableFuture<List<String>> aaaa = net.records(host, 28);
    return CompletableFuture.allOf(srv, a, aaaa)
        .thenApply(v -> {
          if (!srv.join().isEmpty()) {
            List<String> parts = List.of(srv.join().getFirst().strip().split("\\s+", -1));
            String target =
                parts.size() >= 4
                    ? parts.get(3).replaceAll("\\.$", "") + ":" + parts.get(2)
                    : srv.join().getFirst();
            return line(m, Status.PASS, "dns", "dns.srv", "address", host, "target", target);
          }
          if (!a.join().isEmpty() || !aaaa.join().isEmpty()) {
            return line(
                m,
                Status.PASS,
                "dns",
                "dns.address",
                "address",
                host,
                "type",
                !a.join().isEmpty() ? "A" : "AAAA");
          }
          return line(m, Status.FAIL, "dns", "dns.none", "address", host);
        })
        .exceptionally(e -> line(m, Status.WARN, "dns", "dns.failed", "address", host));
  }

  // The map

  private static List<Line> map(State s, Optional<WebConfig> config) {
    Messages m = s.messages();
    if (config.isEmpty()) {
      return List.of();
    }
    Optional<WebSettings.Link> map = config.get().map();
    if (map.isEmpty()) {
      return List.of(line(m, Status.SKIP, "map", "map.none"));
    }
    String url = map.get().url();
    return List.of(
        switch (s.map()) {
          case ALLOWED -> line(m, Status.PASS, "map", "map.ok", "url", url);
          case BLOCKED ->
              line(
                  m,
                  Status.WARN,
                  "map",
                  "map.blocked",
                  "url",
                  url,
                  "dashboard",
                  origin(config.get()));
          case UNREACHABLE -> line(m, Status.FAIL, "map", "map.down", "url", url);
          case CHECKING, UNKNOWN -> line(m, Status.SKIP, "map", "map.unknown", "url", url);
        });
  }

  private static String origin(WebConfig c) {
    return c.publicUrl().map(Discovery::origin).orElse("the dashboard's address");
  }

  // Parts

  private static Line summary(Messages m, List<Line> lines) {
    long pass = lines.stream().filter(l -> l.key().equals(Status.PASS.key())).count();
    long warn = lines.stream().filter(l -> l.key().equals(Status.WARN.key())).count();
    long fail = lines.stream().filter(l -> l.key().equals(Status.FAIL.key())).count();
    return Line.of(
        "check.summary",
        "passed",
        Long.toString(pass),
        "warnings",
        Long.toString(warn),
        "warning-word",
        m.text(warn == 1 ? "check.word.warning" : "check.word.warnings"),
        "problems",
        Long.toString(fail),
        "problem-word",
        m.text(fail == 1 ? "check.word.problem" : "check.word.problems"));
  }

  /** A line of another command's as plain text, its placeholders filled. */
  private static String plain(Messages m, Line l) {
    List<String> pairs = new ArrayList<>();
    l.values()
        .forEach((k, v) -> {
          pairs.add(k);
          pairs.add(v);
        });
    String text = m.text(l.key(), pairs.toArray(String[]::new));
    return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().stripTags(text);
  }

  @SafeVarargs
  private static <T> CompletableFuture<List<T>> done(T... lines) {
    return CompletableFuture.completedFuture(List.of(lines));
  }

  private static String firstLine(@org.jspecify.annotations.Nullable String message) {
    if (message == null) {
      return "unreadable";
    }
    int cut = message.indexOf('\n');
    return (cut < 0 ? message : message.substring(0, cut)).strip();
  }
}
