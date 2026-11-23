// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.integrations.discord;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.log.DebugArea;
import com.petepeterepeat.seasons.core.log.LogThrottle;
import com.petepeterepeat.seasons.core.log.SeasonsLog;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
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

/**
 * Posts to Discord webhooks off the server thread. Each webhook has a lane of its own: one thread,
 * one post at a time and in order, with a bounded queue and backoff on failure, so a webhook
 * Discord is rate-limiting, such as the admin one during a burst of alerts, never holds up the
 * public channel's posts. Every post disables mentions, so text such as {@code @everyone} can't
 * ping anyone. The URL's token is never logged: it is the secret, so the log names a webhook by its
 * host and id only. A failing webhook is warned about at most once every five minutes, with a count
 * of the failures left out (questions 295); retries are debug lines.
 */
public final class DiscordWebhooks implements Webhooks {
  private static final Logger LOG = SeasonsLog.get();
  private static final int QUEUE = 100;
  private static final int MAX_CONTENT = 2000;
  private static final Pattern RETRY_AFTER = Pattern.compile("\"retry_after\"\\s*:\\s*([0-9.]+)");

  private final HttpClient client;
  private final List<Duration> backoff;
  private final Map<URI, ThreadPoolExecutor> lanes = new ConcurrentHashMap<>();
  private final AtomicInteger lanesMade = new AtomicInteger();
  private final LogThrottle throttle = new LogThrottle(Clock.systemUTC(), Duration.ofMinutes(5));
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
    lanes.computeIfAbsent(webhook, url -> lane(url)).execute(() -> send(webhook, body));
  }

  /** One webhook's thread and queue. */
  private ThreadPoolExecutor lane(URI webhook) {
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
            warn(webhook, "its queue is full, so a post was dropped");
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

  /** A warning about one webhook, at most once every five minutes for it. */
  private void warn(URI webhook, String what) {
    String name = redact(webhook);
    throttle
        .admit(name)
        .ifPresent(skipped ->
            LOG.warn("Discord webhook {}: {}{}", name, what, LogThrottle.repeats(skipped)));
  }

  /**
   * A webhook as the log names it: its host and id, with the token, the secret, left out. For
   * example {@code discord.com/api/webhooks/123456/****}.
   */
  static String redact(URI webhook) {
    String path = Optional.ofNullable(webhook.getPath()).orElse("");
    int last = path.lastIndexOf('/');
    String kept = last > 0 ? path.substring(0, last) : "";
    return Optional.ofNullable(webhook.getHost()).orElse("?") + kept + "/****";
  }

  /** The JSON a webhook takes: the text, with every kind of mention turned off. */
  static String body(String content) {
    String text = content.length() > MAX_CONTENT ? content.substring(0, MAX_CONTENT) : content;
    return "{\"content\":" + quote(text) + ",\"allowed_mentions\":{\"parse\":[]}}";
  }

  private void send(URI webhook, String body) {
    String last = "";
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
          SeasonsLog.debug(DebugArea.DISCORD, "Posted to {}", redact(webhook));
          return;
        }
        if (status == 429) {
          retryIn = Optional.of(retryAfter(response.body()));
        } else if (status >= 500) {
          retryIn = Optional.empty();
        } else {
          warn(
              webhook,
              "Discord refused a post with status "
                  + status
                  + "; check the webhook URL in secrets.yml");
          return;
        }
        last = "status " + status;
      } catch (IOException e) {
        last = e.getClass().getSimpleName();
        retryIn = Optional.empty();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      if (attempt >= backoff.size()) {
        warn(
            webhook, "gave up on a post after " + (attempt + 1) + " attempts (last: " + last + ")");
        return;
      }
      SeasonsLog.debug(
          DebugArea.DISCORD, "A post to {} failed ({}); will retry", redact(webhook), last);
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
