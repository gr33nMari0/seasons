// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.integrations.discord;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DiscordWebhooksTest {
  private HttpServer server;
  private final List<String> bodies = new CopyOnWriteArrayList<>();
  private final AtomicInteger failuresLeft = new AtomicInteger();
  private DiscordWebhooks webhooks;

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/hook",
        exchange -> {
          String body = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
          if (failuresLeft.getAndDecrement() > 0) {
            byte[] reply = "{\"retry_after\": 0.05}".getBytes(UTF_8);
            exchange.sendResponseHeaders(429, reply.length);
            exchange.getResponseBody().write(reply);
          } else {
            bodies.add(body);
            exchange.sendResponseHeaders(204, -1);
          }
          exchange.close();
        });
    server.start();
    webhooks =
        new DiscordWebhooks(
            HttpClient.newHttpClient(), List.of(Duration.ofMillis(10), Duration.ofMillis(10)));
  }

  @AfterEach
  void tearDown() {
    webhooks.close();
    server.stop(0);
  }

  private URI url() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
  }

  @Test
  void postsTheTextWithMentionsOff() {
    webhooks.post(url(), "Season 2 ends in 7 days @everyone \"quoted\"\nnext");
    webhooks.close();

    assertThat(bodies)
        .containsExactly(
            "{\"content\":\"Season 2 ends in 7 days @everyone \\\"quoted\\\"\\nnext\",\"allowed_mentions\":{\"parse\":[]}}");
  }

  @Test
  void retriesWhenRateLimitedAndKeepsOrder() {
    failuresLeft.set(2);
    webhooks.post(url(), "one");
    webhooks.post(url(), "two");
    webhooks.close();

    assertThat(bodies).hasSize(2);
    assertThat(bodies.get(0)).contains("\"one\"");
    assertThat(bodies.get(1)).contains("\"two\"");
  }

  @Test
  void givesUpAfterTheLastRetry() {
    failuresLeft.set(10);
    webhooks.post(url(), "lost");
    webhooks.close();

    assertThat(bodies).isEmpty();
  }

  @Test
  void eachWebhookHasItsOwnLane() throws Exception {
    // The admin webhook stalls; the public one's post goes out all the same.
    CountDownLatch release = new CountDownLatch(1);
    server.stop(0);
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.setExecutor(Executors.newFixedThreadPool(4));
    server.createContext(
        "/stalled",
        exchange -> {
          try {
            release.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.createContext(
        "/hook",
        exchange -> {
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.start();
    URI stalled = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/stalled");

    webhooks.post(stalled, "alert");
    webhooks.post(url(), "public");
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (bodies.isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    release.countDown();

    assertThat(bodies).singleElement().asString().contains("\"public\"");
    assertThat(webhooks.lanes()).isEqualTo(2);
  }

  @Test
  void aPostAfterCloseIsDropped() {
    webhooks.close();
    webhooks.post(url(), "late");

    assertThat(bodies).isEmpty();
  }

  @Test
  void escapesControlCharacters() {
    assertThat(DiscordWebhooks.quote("a\u0001b\\")).isEqualTo("\"a\\u0001b\\\\\"");
  }
}
