// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * The open event streams. Each client has a bounded queue that drops its oldest event when it falls
 * behind, so a slow client never holds up the others or grows without bound; and each visitor,
 * account and the server as a whole may hold only so many open (web.yml {@code stream}).
 */
final class EventHub {
  /** Stops a client's loop. */
  static final String CLOSE = "";

  record Limits(int perIp, int perAccount, int total, int queue) {}

  /** One open stream. */
  static final class Client {
    final String ip;
    final Optional<UUID> account;
    final Predicate<String> sees;
    private final LinkedBlockingDeque<String> queue;
    volatile boolean closed;

    Client(String ip, Optional<UUID> account, Predicate<String> sees, int capacity) {
      this.ip = ip;
      this.account = account;
      this.sees = sees;
      this.queue = new LinkedBlockingDeque<>(Math.max(1, capacity));
    }

    void offer(String frame) {
      while (!queue.offerLast(frame)) {
        queue.pollFirst();
      }
    }

    /** The next frame, or empty when {@code wait} passes with none. */
    Optional<String> next(Duration wait) throws InterruptedException {
      if (closed) {
        return Optional.of(CLOSE);
      }
      return Optional.ofNullable(
          queue.pollFirst(Math.max(1, wait.toMillis()), TimeUnit.MILLISECONDS));
    }

    int queued() {
      return queue.size();
    }
  }

  private final Set<Client> clients = ConcurrentHashMap.newKeySet();
  private volatile Limits limits;
  private long sequence;

  EventHub(Limits limits) {
    this.limits = limits;
  }

  void limits(Limits next) {
    this.limits = next;
  }

  /**
   * Opens a stream, or refuses it when the visitor, the account or the server holds its limit.
   *
   * @param sees whether this client sees a visibility key
   */
  synchronized Optional<Client> open(String ip, Optional<UUID> account, Predicate<String> sees) {
    Limits l = limits;
    long fromIp = clients.stream().filter(c -> c.ip.equals(ip)).count();
    long fromAccount =
        account.isEmpty() ? 0 : clients.stream().filter(c -> c.account.equals(account)).count();
    if (clients.size() >= l.total() || fromIp >= l.perIp() || fromAccount >= l.perAccount()) {
      return Optional.empty();
    }
    Client client = new Client(ip, account, sees, l.queue());
    clients.add(client);
    return Optional.of(client);
  }

  void close(Client client) {
    client.closed = true;
    clients.remove(client);
  }

  /** Sends an event to every client that sees its visibility key. */
  void publish(String visibilityKey, String event, JsonObject data) {
    String frame;
    synchronized (this) {
      frame = "id: " + ++sequence + "\nevent: " + event + "\ndata: " + data + "\n\n";
    }
    for (Client c : clients) {
      if (c.sees.test(visibilityKey)) {
        c.offer(frame);
      }
    }
  }

  /** Closes every stream, as Seasons Web stops or reloads. */
  void closeAll() {
    for (Client c : clients) {
      c.closed = true;
      c.offer(CLOSE);
    }
    clients.clear();
  }

  /** Ends a player's streams, as they log out. */
  void closeAccount(UUID player) {
    for (Client c : List.copyOf(clients)) {
      if (c.account.equals(Optional.of(player))) {
        c.closed = true;
        c.offer(CLOSE);
        clients.remove(c);
      }
    }
  }

  int open() {
    return clients.size();
  }

  Map<String, Long> byIp() {
    Map<String, Long> counts = new java.util.HashMap<>();
    clients.forEach(c -> counts.merge(c.ip, 1L, Long::sum));
    return counts;
  }
}
