// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

/**
 * Looks at the map's headers on a thread of its own, at start and every 10 minutes, so the
 * dashboard frames the map only when the map lets it ({@link Framing}). It sends nothing about
 * players: one GET with Seasons Web's name as the user agent, the body thrown away.
 */
final class MapProbe implements AutoCloseable {
  static final Duration EVERY = Duration.ofMinutes(10);
  static final Duration TIMEOUT = Duration.ofSeconds(3);

  private final String url;
  private final Optional<String> dashboard;
  private final String version;
  private final Logger log;
  private final ScheduledExecutorService timer;
  private final HttpClient http;
  private volatile Framing.State state = Framing.State.CHECKING;

  /**
   * @param url the map's address
   * @param dashboard the dashboard's own origin, from public-url
   */
  MapProbe(String url, Optional<String> dashboard, String version, Logger log) {
    this.url = url;
    this.dashboard = dashboard;
    this.version = version;
    this.log = log;
    this.timer =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("SeasonsWeb-map-", 0).factory());
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
  }

  void start() {
    var _ = timer.scheduleWithFixedDelay(this::look, 0, EVERY.toSeconds(), TimeUnit.SECONDS);
  }

  Framing.State state() {
    return state;
  }

  private void look() {
    Framing.State next;
    try {
      HttpResponse<Void> answer =
          http.send(
              HttpRequest.newBuilder(URI.create(url))
                  .timeout(TIMEOUT)
                  .header("User-Agent", "SeasonsWeb/" + version)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      next =
          Framing.decide(
              answer.headers().allValues("Content-Security-Policy"),
              answer.headers().firstValue("X-Frame-Options"),
              url,
              dashboard);
    } catch (HttpTimeoutException e) {
      next = Framing.State.UNKNOWN;
    } catch (ConnectException e) {
      next = Framing.State.UNREACHABLE;
    } catch (IOException | IllegalArgumentException e) {
      next = Framing.State.UNREACHABLE;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    if (next != state) {
      log.info("Seasons Web: the map at {} is {}", url, next.text());
    }
    state = next;
  }

  @Override
  public void close() {
    timer.shutdownNow();
    http.close();
  }
}
