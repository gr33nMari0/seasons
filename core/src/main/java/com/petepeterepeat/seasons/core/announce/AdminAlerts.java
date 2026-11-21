// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.announce;

import com.petepeterepeat.seasons.core.config.ConfigSnapshot;
import com.petepeterepeat.seasons.core.port.Scheduler;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The admin webhook's alerts: tripwire hits, reconciliation repairs, recovery runs and curation
 * actions, with text from messages.yml. Alerts that come within {@link #GATHER} of each other go
 * out as one post, a line each, so a burst such as a reconciliation pass doesn't run into Discord's
 * rate limit; a post never passes Discord's 2,000 characters. Without an admin webhook nothing is
 * posted: each alert is already in the server log and the audit log.
 */
public final class AdminAlerts {
  /** How long an alert waits for others to go with it. */
  public static final Duration GATHER = Duration.ofSeconds(2);

  /** Discord's limit on a post, less room to spare. */
  static final int MAX_POST = 1900;

  /** Alerts that post nothing, for tests and for a server without the admin webhook wiring. */
  public static final AdminAlerts NONE = new AdminAlerts();

  private final @Nullable Scheduler scheduler;
  private final Webhooks webhooks;
  private final @Nullable Supplier<ConfigSnapshot> config;
  private final List<String> waiting = new ArrayList<>();
  private boolean flushing;

  public AdminAlerts(Scheduler scheduler, Webhooks webhooks, Supplier<ConfigSnapshot> config) {
    this.scheduler = scheduler;
    this.webhooks = webhooks;
    this.config = config;
  }

  private AdminAlerts() {
    this.scheduler = null;
    this.webhooks = Webhooks.NONE;
    this.config = null;
  }

  /** Queues one alert, if there is an admin webhook. Any thread. */
  public void alert(String key, String... placeholders) {
    Supplier<ConfigSnapshot> settings = config;
    Scheduler later = scheduler;
    if (settings == null || later == null) {
      return;
    }
    ConfigSnapshot snapshot = settings.get();
    if (snapshot.secrets().adminWebhook().isEmpty()) {
      return;
    }
    String line = snapshot.messages().text(key, placeholders);
    synchronized (waiting) {
      waiting.add(line);
      if (flushing) {
        return;
      }
      flushing = true;
    }
    later.runGlobalLater(GATHER, this::flush);
  }

  /** Posts what is waiting, in posts of at most {@link #MAX_POST} characters; also at shutdown. */
  public void flush() {
    Supplier<ConfigSnapshot> settings = config;
    if (settings == null) {
      return;
    }
    List<String> lines;
    synchronized (waiting) {
      lines = List.copyOf(waiting);
      waiting.clear();
      flushing = false;
    }
    Optional<URI> url = settings.get().secrets().adminWebhook();
    if (url.isEmpty()) {
      return;
    }
    for (String post : posts(lines)) {
      webhooks.post(url.get(), post);
    }
  }

  /** Lines joined into posts of at most {@link #MAX_POST} characters; a longer line is cut. */
  static List<String> posts(List<String> lines) {
    List<String> posts = new ArrayList<>();
    StringBuilder post = new StringBuilder();
    for (String line : lines) {
      String cut = line.length() > MAX_POST ? line.substring(0, MAX_POST - 1) + "…" : line;
      if (!post.isEmpty() && post.length() + 1 + cut.length() > MAX_POST) {
        posts.add(post.toString());
        post.setLength(0);
      }
      if (!post.isEmpty()) {
        post.append('\n');
      }
      post.append(cut);
    }
    if (!post.isEmpty()) {
      posts.add(post.toString());
    }
    return posts;
  }
}
