// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.integrations.discord;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Posts to Discord webhooks off the server thread. Each webhook has a lane of its own: one thread,
 * one post at a time and in order, with a bounded queue and backoff on failure, so a webhook
 * Discord is rate-limiting, such as the admin one during a burst of alerts, never holds up the
 * public channel's posts. Every post disables mentions, so text such as {@code @everyone} can't
 * ping anyone. The URL is never logged: it is the secret.
 */
public final class DiscordWebhooks implements Webhooks {
  private static final Logger LOG = LoggerFactory.getLogger(DiscordWebhooks.class);
  private static final int QUEUE = 100;
  private static final int MAX_CONTENT = 2000;
  private static final Pattern RETRY_AFTER = Pattern.compile("\"retry_after\"\\s*:\\s*([0-9.]+)");

  private final HttpClient client;
  private final List<Duration> backoff;
  private final Map<URI, ThreadPoolExecutor> lanes = new ConcurrentHashMap<>();
  private final AtomicInteger lanesMade = new AtomicInteger();
  private volatile boolean closed;

  public DiscordWebhooks() {
    this(
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
        List.of(
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(4),
            Duration.ofSeconds(8)));
  }

  /**
   * @param backoff the wait before each retry; one more attempt than entries is made
   */
  DiscordWebhooks(HttpClient client, List<Duration> backoff) {
    this.client = client;
    this.backoff = List.copyOf(backoff);
  }

  @Override
  public void post(URI webhook, String content) {
    if (closed) {
      return;
    }
    String body = body(content);
    lanes.computeIfAbsent(webhook, url -> lane()).execute(() -> send(webhook, body));
  }

  /** One webhook's thread and queue. */
  private ThreadPoolExecutor lane() {
    return new ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(QUEUE),
        Thread.ofPlatform()
            .name("seasons-discord-" + lanesMade.incrementAndGet())
            .daemon(true)
            .factory(),
        (task, pool) -> {
          if (!pool.isShutdown()) {
            LOG.warn("A Discord webhook's queue is full; dropping a post");
          }
        });
  }

  /** How many webhooks have a lane, for tests. */
  int lanes() {
    return lanes.size();
  }

  @Override
  public void close() {
    closed = true;
    lanes.values().forEach(ThreadPoolExecutor::shutdown);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    try {
      for (ThreadPoolExecutor lane : lanes.values()) {
        long left = deadline - System.nanoTime();
        if (left <= 0 || !lane.awaitTermination(left, TimeUnit.NANOSECONDS)) {
          lane.shutdownNow();
        }
      }
    } catch (InterruptedException e) {
      lanes.values().forEach(ThreadPoolExecutor::shutdownNow);
      Thread.currentThread().interrupt();
    }
  }

  /** The JSON a webhook takes: the text, with every kind of mention turned off. */
  static String body(String content) {
    String text = content.length() > MAX_CONTENT ? content.substring(0, MAX_CONTENT) : content;
    return "{\"content\":" + quote(text) + ",\"allowed_mentions\":{\"parse\":[]}}";
  }

  private void send(URI webhook, String body) {
    for (int attempt = 0; ; attempt++) {
      Optional<Duration> retryIn;
      try {
        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(webhook)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status / 100 == 2) {
          return;
        }
        if (status == 429) {
          retryIn = Optional.of(retryAfter(response.body()));
        } else if (status >= 500) {
          retryIn = Optional.empty();
        } else {
          LOG.warn("Discord refused a post with status {}; not retrying", status);
          return;
        }
        LOG.warn("Discord answered {}; will retry", status);
      } catch (IOException e) {
        LOG.warn("Can't reach Discord ({}); will retry", e.getClass().getSimpleName());
        retryIn = Optional.empty();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      if (attempt >= backoff.size()) {
        LOG.error("Giving up on a Discord post after {} attempts", attempt + 1);
        return;
      }
      try {
        Thread.sleep(retryIn.orElse(backoff.get(attempt)));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private static Duration retryAfter(String body) {
    Matcher matcher = RETRY_AFTER.matcher(body);
    if (matcher.find()) {
      double seconds = Double.parseDouble(matcher.group(1));
      return Duration.ofMillis((long) Math.min(60_000, Math.ceil(seconds * 1000)));
    }
    return Duration.ofSeconds(1);
  }

  static String quote(String text) {
    StringBuilder out = new StringBuilder("\"");
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }
}
