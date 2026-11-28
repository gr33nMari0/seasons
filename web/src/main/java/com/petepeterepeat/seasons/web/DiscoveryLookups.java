// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The real look-ups behind {@code /web discovery check}: TXT records through the same public
 * DNS-over-HTTPS resolvers the connect page uses (Cloudflare, then Google), so the check sees what
 * players' browsers will, and the dashboard's well-known reply over https; on virtual threads,
 * never the server thread, with short timeouts. Only the record's name is sent, and only when an
 * admin runs the check.
 */
final class DiscoveryLookups
    implements Discovery.Resolver, Discovery.Asker, WebCheck.Network, AutoCloseable {
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final List<String> RESOLVERS =
      List.of("https://cloudflare-dns.com/dns-query", "https://dns.google/resolve");
  private static final int TXT = 16;

  private final ExecutorService threads =
      Executors.newThreadPerTaskExecutor(
          Thread.ofVirtual().name("SeasonsWeb-discovery-", 0).factory());
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(TIMEOUT)
          .followRedirects(HttpClient.Redirect.NEVER)
          .version(HttpClient.Version.HTTP_1_1)
          .executor(threads)
          .build();
  private final String version;

  DiscoveryLookups(String version) {
    this.version = version;
  }

  @Override
  public CompletableFuture<List<String>> txt(String name) {
    return records(name, TXT);
  }

  /** {@code /web check}'s look-ups of A, AAAA and SRV records, the same way as TXT. */
  @Override
  public CompletableFuture<List<String>> records(String name, int type) {
    return recordsFrom(RESOLVERS.getFirst(), name, type)
        .exceptionallyCompose(first -> recordsFrom(RESOLVERS.get(1), name, type));
  }

  /** {@code /web check}'s fetch of the dashboard's own public address. */
  @Override
  public CompletableFuture<WebCheck.Fetched> fetch(String url) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(TIMEOUT)
            .header("Accept", "application/json")
            .header("User-Agent", "SeasonsWeb/" + version + " check")
            .GET()
            .build();
    return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(answer ->
            new WebCheck.Fetched(answer.statusCode(), answer.body(), Optional.<String>empty()))
        .exceptionally(e -> {
          Throwable cause = e.getCause() == null ? e : e.getCause();
          String why = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
          return new WebCheck.Fetched(0, "", Optional.of(why));
        });
  }

  /** One resolver's answer of a type for a name: empty when the name has none. */
  private CompletableFuture<List<String>> recordsFrom(String resolver, String name, int type) {
    String query =
        resolver + "?name=" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "&type=" + type;
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(query))
            .timeout(TIMEOUT)
            .header("Accept", "application/dns-json")
            .GET()
            .build();
    return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(answer -> {
          if (answer.statusCode() != 200) {
            throw new IllegalStateException(resolver + " answered " + answer.statusCode());
          }
          JsonObject o = JsonParser.parseString(answer.body()).getAsJsonObject();
          List<String> out = new ArrayList<>();
          if (o.get("Answer") instanceof JsonArray records) {
            for (JsonElement record : records) {
              JsonObject r = record.getAsJsonObject();
              if (r.has("type") && r.get("type").getAsInt() == type && r.has("data")) {
                out.add(r.get("data").getAsString());
              }
            }
          }
          return List.copyOf(out);
        });
  }

  @Override
  public CompletableFuture<Optional<List<String>>> gameAddresses(String origin) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(origin + Routes.WELL_KNOWN))
            .timeout(TIMEOUT)
            .header("Accept", "application/json")
            .header("User-Agent", "SeasonsWeb/" + version + " discovery check")
            .GET()
            .build();
    return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(answer -> {
          if (answer.statusCode() != 200) {
            return Optional.<List<String>>empty();
          }
          try {
            JsonObject o = JsonParser.parseString(answer.body()).getAsJsonObject();
            if (!o.has("service") || !o.get("service").getAsString().equals("seasons")) {
              return Optional.<List<String>>empty();
            }
            List<String> listed = new ArrayList<>();
            if (o.get("gameAddresses") instanceof JsonArray games) {
              for (JsonElement game : games) {
                listed.add(game.getAsString());
              }
            }
            return Optional.of(List.copyOf(listed));
          } catch (RuntimeException e) {
            return Optional.<List<String>>empty();
          }
        })
        .exceptionally(e -> Optional.empty());
  }

  @Override
  public void close() {
    threads.shutdownNow();
  }
}
