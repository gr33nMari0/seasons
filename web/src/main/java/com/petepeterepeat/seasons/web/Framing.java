// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Whether the map's own headers let the dashboard frame it (docs/questions.md, M7 part 2): a
 * browser can't tell a page's script that a frame was refused, so Seasons Web reads the map's
 * {@code Content-Security-Policy} {@code frame-ancestors}, which wins when present, and otherwise
 * its {@code X-Frame-Options}.
 */
final class Framing {
  /** What the dashboard does with the map. */
  enum State {
    /** Not looked at yet. */
    CHECKING,
    /** Its headers let the dashboard frame it. */
    ALLOWED,
    /** Its headers refuse: a link instead. */
    BLOCKED,
    /** Nothing answered: a link instead. */
    UNREACHABLE,
    /** It answered too slowly to tell: framed, with the link as a fallback. */
    UNKNOWN;

    String text() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private Framing() {}

  /**
   * Whether a page at {@code map} with these headers may be framed by a page at {@code dashboard}.
   *
   * @param csp every Content-Security-Policy header the map sent
   * @param frameOptions its X-Frame-Options header, if any
   * @param dashboard the dashboard's own origin, such as {@code https://seasons.example.com}; empty
   *     when it has no public-url, so only an unrestricted map is known to be framable
   */
  static State decide(
      List<String> csp, Optional<String> frameOptions, String map, Optional<String> dashboard) {
    Optional<List<String>> ancestors = frameAncestors(csp);
    if (ancestors.isPresent()) {
      if (dashboard.isEmpty()) {
        return ancestors.get().contains("*") ? State.ALLOWED : State.UNKNOWN;
      }
      return allowedBy(ancestors.get(), map, dashboard.get()) ? State.ALLOWED : State.BLOCKED;
    }
    if (frameOptions.isPresent()) {
      String xfo = frameOptions.get().strip().toUpperCase(Locale.ROOT);
      if (xfo.equals("DENY")) {
        return State.BLOCKED;
      }
      if (xfo.equals("SAMEORIGIN")) {
        if (dashboard.isEmpty()) {
          return State.UNKNOWN;
        }
        return origin(map).equals(dashboard.get()) ? State.ALLOWED : State.BLOCKED;
      }
    }
    return State.ALLOWED;
  }

  /** The sources of the first frame-ancestors directive across the policies, if any has one. */
  static Optional<List<String>> frameAncestors(List<String> policies) {
    for (String policy : policies) {
      for (String directive : policy.split(";", -1)) {
        String[] words = directive.strip().split("\\s+");
        if (words.length > 0 && words[0].equalsIgnoreCase("frame-ancestors")) {
          return Optional.of(
              List.of(words).subList(1, words.length).stream()
                  .map(w -> w.toLowerCase(Locale.ROOT))
                  .toList());
        }
      }
    }
    return Optional.empty();
  }

  private static boolean allowedBy(List<String> sources, String map, String dashboard) {
    URI page = URI.create(dashboard);
    for (String source : sources) {
      if (source.equals("*")) {
        return true;
      }
      if (source.equals("'none'")) {
        return false;
      }
      if (source.equals("'self'")) {
        if (origin(map).equals(dashboard)) {
          return true;
        }
        continue;
      }
      if (source.equals("https:") || source.equals(page.getScheme() + ":")) {
        return true;
      }
      if (matches(source, page)) {
        return true;
      }
    }
    return false;
  }

  /** A host source such as {@code https://seasons.example.com} or {@code https://*.example.com}. */
  private static boolean matches(String source, URI page) {
    String s = source;
    String scheme = null;
    int sep = s.indexOf("://");
    if (sep > 0) {
      scheme = s.substring(0, sep);
      s = s.substring(sep + 3);
    }
    if (scheme != null && !scheme.equals(page.getScheme())) {
      return false;
    }
    int slash = s.indexOf('/');
    if (slash >= 0) {
      s = s.substring(0, slash);
    }
    String host = s;
    String port = null;
    int colon = s.lastIndexOf(':');
    if (colon > 0) {
      host = s.substring(0, colon);
      port = s.substring(colon + 1);
    }
    int pagePort = page.getPort() > 0 ? page.getPort() : 443;
    if (port != null && !port.equals("*") && !port.equals(Integer.toString(pagePort))) {
      return false;
    }
    if (port == null && pagePort != 443) {
      return false;
    }
    String pageHost = page.getHost().toLowerCase(Locale.ROOT);
    if (host.startsWith("*.")) {
      return pageHost.endsWith(host.substring(1));
    }
    return pageHost.equals(host);
  }

  static String origin(String url) {
    return WebConfig.origin(URI.create(url));
  }
}
