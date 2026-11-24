// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finding a dashboard from the address players join the game with (questions 336). The public
 * connect page looks up {@code _seasons.<game address>} for a TXT record {@code v=seasons1;
 * url=<public-url>}, then asks that dashboard's {@code /.well-known/seasons.json} whether it lists
 * the address typed in {@code gameAddresses}. {@code /web discovery} prints the records to publish,
 * and {@code /web discovery check} looks them up from here and asks the dashboard the same
 * question.
 */
final class Discovery {
  /** The record's version tag: a future format gets a new one. */
  static final String VERSION = "v=seasons1";

  private static final Pattern HOST =
      Pattern.compile(
          "(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+");
  private static final Pattern URL = Pattern.compile("(?:^|;)\\s*url=([^;\\s]+)");

  private Discovery() {}

  /**
   * A game address as players type it, normalised: lower case, no trailing dot, and the default
   * port 25565 dropped. Empty for anything that isn't a host name with an optional port; an IP
   * address has no DNS name to hold a record.
   */
  static Optional<String> normalise(String typed) {
    String t = typed.trim().toLowerCase(Locale.ROOT);
    String host = t;
    String port = "";
    int colon = t.lastIndexOf(':');
    if (colon > 0) {
      host = t.substring(0, colon);
      port = t.substring(colon + 1);
      if (!port.matches("\\d{1,5}")
          || Integer.parseInt(port) < 1
          || Integer.parseInt(port) > 65535) {
        return Optional.empty();
      }
      if (port.equals("25565")) {
        port = "";
      }
    }
    host = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    if (!HOST.matcher(host).matches() || host.matches("[\\d.]+")) {
      return Optional.empty();
    }
    return Optional.of(port.isEmpty() ? host : host + ":" + port);
  }

  /** The host part of a normalised game address, which the record is named after. */
  static String host(String address) {
    int colon = address.indexOf(':');
    return colon < 0 ? address : address.substring(0, colon);
  }

  /** The DNS name a game address's record goes on. */
  static String recordName(String address) {
    return "_seasons." + host(address);
  }

  /** The record's text for a dashboard. */
  static String recordValue(URI publicUrl) {
    return VERSION + "; url=" + origin(publicUrl);
  }

  /** {@code https://host[:port]}, without a path. */
  static String origin(URI url) {
    return url.getScheme() + "://" + url.getRawAuthority();
  }

  /** The dashboard a TXT record names, if it is a Seasons record at all. */
  static Optional<String> dashboard(String txt) {
    String t = txt.replace("\"", "").trim();
    if (!t.startsWith(VERSION)) {
      return Optional.empty();
    }
    Matcher m = URL.matcher(t);
    return m.find() ? Optional.of(m.group(1)) : Optional.empty();
  }

  /** Looks up a name's TXT records. */
  @FunctionalInterface
  interface Resolver {
    CompletableFuture<List<String>> txt(String name);
  }

  /** Asks a dashboard origin for its well-known reply, as its {@code gameAddresses}. */
  @FunctionalInterface
  interface Asker {
    /** Empty when it doesn't answer as a Seasons dashboard. */
    CompletableFuture<Optional<List<String>>> gameAddresses(String origin);
  }

  /** What {@code /web discovery}, with no check, tells an admin: the records to publish. */
  static List<Line> records(List<String> addresses, Optional<URI> publicUrl) {
    if (publicUrl.isEmpty()) {
      return List.of(Line.of("discovery.no-url"));
    }
    if (addresses.isEmpty()) {
      return List.of(Line.of("discovery.no-addresses"));
    }
    List<Line> lines = new ArrayList<>();
    lines.add(Line.of("discovery.header"));
    List<String> named = new ArrayList<>();
    for (String address : addresses) {
      // Addresses on one host, such as two ports, share its record.
      if (named.contains(recordName(address))) {
        continue;
      }
      named.add(recordName(address));
      lines.add(
          Line.of(
              "discovery.record",
              "address",
              address,
              "name",
              recordName(address) + ".",
              "value",
              recordValue(publicUrl.get())));
    }
    lines.add(Line.of("discovery.then-check"));
    return lines;
  }

  /** What {@code /web discovery check} finds for each game address. */
  static CompletableFuture<List<Line>> check(
      List<String> addresses, Optional<URI> publicUrl, Resolver resolver, Asker asker) {
    if (publicUrl.isEmpty()) {
      return CompletableFuture.completedFuture(List.of(Line.of("discovery.no-url")));
    }
    if (addresses.isEmpty()) {
      return CompletableFuture.completedFuture(List.of(Line.of("discovery.no-addresses")));
    }
    String ours = origin(publicUrl.get());
    List<CompletableFuture<Line>> each = new ArrayList<>();
    for (String address : addresses) {
      each.add(
          resolver
              .txt(recordName(address))
              .thenCompose(records -> {
                Optional<String> named =
                    records.stream()
                        .map(Discovery::dashboard)
                        .flatMap(Optional::stream)
                        .findFirst();
                if (named.isEmpty()) {
                  return CompletableFuture.completedFuture(
                      Line.of(
                          "discovery.missing", "address", address, "name", recordName(address)));
                }
                if (!named.get().replaceAll("/+$", "").equals(ours)) {
                  return CompletableFuture.completedFuture(
                      Line.of(
                          "discovery.elsewhere",
                          "address",
                          address,
                          "url",
                          named.get(),
                          "ours",
                          ours));
                }
                return asker
                    .gameAddresses(ours)
                    .thenApply(listed -> {
                      if (listed.isEmpty()) {
                        return Line.of("discovery.unreachable", "address", address, "url", ours);
                      }
                      if (!listed.get().contains(address)) {
                        return Line.of("discovery.not-listed", "address", address);
                      }
                      return Line.of("discovery.ok", "address", address, "url", ours);
                    });
              })
              .exceptionally(e -> Line.of("discovery.lookup-failed", "address", address)));
    }
    return CompletableFuture.allOf(each.toArray(CompletableFuture[]::new))
        .thenApply(v -> each.stream().map(CompletableFuture::join).toList());
  }
}
