// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Device-code login (spec, Login): the dashboard asks for a code, the player types it with {@code
 * /web link}, and the browser that asked, holding the device secret in its cookie, collects the
 * session at its next poll. Codes are 8 characters with no look-alikes, single-use, and expire
 * after 5 minutes. Kept in memory only: a restart forgets them.
 */
final class DeviceCodes {
  /** No 0, O, 1, I, L or U. */
  static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ";

  static final int LENGTH = 8;
  static final Duration LIFETIME = Duration.ofMinutes(5);

  /** How often the dashboard should poll. */
  static final Duration INTERVAL = Duration.ofSeconds(2);

  /** Polls closer than this together are refused. */
  private static final Duration TOO_SOON = Duration.ofSeconds(1);

  private final Clock clock;

  /** By code, without its dash. */
  private final Map<String, Pending> pending = new ConcurrentHashMap<>();

  private static final class Pending {
    final String deviceHash;
    final Instant expires;
    Optional<Linked> linked = Optional.empty();
    boolean collected;
    Optional<Instant> lastPoll = Optional.empty();

    Pending(String deviceHash, Instant expires) {
      this.deviceHash = deviceHash;
      this.expires = expires;
    }
  }

  /** Who linked a code, and whether they held seasons.web.admin then. */
  record Linked(UUID player, String name, boolean admin) {}

  /** A new code and the secret for the browser's cookie. */
  record Issued(String code, String deviceSecret) {
    /** As the dashboard shows it, such as {@code K7QX-2MPA}. */
    String shown() {
      return code.substring(0, 4) + "-" + code.substring(4);
    }
  }

  /** What a poll finds. */
  sealed interface Poll {}

  record Waiting() implements Poll {}

  record Collected(Linked linked) implements Poll {}

  record Expired() implements Poll {}

  record Unknown() implements Poll {}

  record TooSoon() implements Poll {}

  DeviceCodes(Clock clock) {
    this.clock = clock;
  }

  Issued issue() {
    sweep();
    String code;
    do {
      StringBuilder b = new StringBuilder(LENGTH);
      for (int i = 0; i < LENGTH; i++) {
        b.append(ALPHABET.charAt(Tokens.nextInt(ALPHABET.length())));
      }
      code = b.toString();
    } while (pending.containsKey(code));
    String secret = Tokens.secret();
    pending.put(code, new Pending(Tokens.hash(secret), clock.instant().plus(LIFETIME)));
    return new Issued(code, secret);
  }

  /** A code as a player types it: any case, with or without the dash or spaces. */
  static String normalise(String typed) {
    return typed.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
  }

  /** {@code /web link}: true if the code was waiting and now belongs to this player. */
  boolean link(String typed, Linked who) {
    Pending p = pending.get(normalise(typed));
    if (p == null) {
      return false;
    }
    synchronized (p) {
      if (p.linked.isPresent() || !clock.instant().isBefore(p.expires)) {
        return false;
      }
      p.linked = Optional.of(who);
      return true;
    }
  }

  /** The browser's poll, with its device secret. */
  Poll poll(String deviceSecret) {
    String hash = Tokens.hash(deviceSecret);
    Instant now = clock.instant();
    for (Pending p : pending.values()) {
      if (!Tokens.same(p.deviceHash, hash)) {
        continue;
      }
      synchronized (p) {
        if (p.collected || !now.isBefore(p.expires)) {
          return new Expired();
        }
        if (p.lastPoll.isPresent() && now.isBefore(p.lastPoll.get().plus(TOO_SOON))) {
          return new TooSoon();
        }
        p.lastPoll = Optional.of(now);
        if (p.linked.isPresent()) {
          p.collected = true;
          return new Collected(p.linked.get());
        }
        return new Waiting();
      }
    }
    return new Unknown();
  }

  /** Forgets codes long past, keeping expired ones a while so a late poll hears they expired. */
  private void sweep() {
    Instant cut = clock.instant().minus(LIFETIME);
    for (Iterator<Pending> it = pending.values().iterator(); it.hasNext(); ) {
      if (it.next().expires.isBefore(cut)) {
        it.remove();
      }
    }
  }

  void clear() {
    pending.clear();
  }
}
