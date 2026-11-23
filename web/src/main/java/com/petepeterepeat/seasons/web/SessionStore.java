// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;

/**
 * Dashboard sessions, kept in {@code <world>/seasons-web/sessions.json} with each token stored only
 * as its SHA-256. A session lasts {@code sessions.lifetime} from its login; last-seen times are
 * saved at most every five minutes.
 */
final class SessionStore {
  static final int FORMAT_VERSION = 1;
  private static final Duration SAVE_LAST_SEEN = Duration.ofMinutes(5);
  private static final String ID_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

  /**
   * One session.
   *
   * @param id a short id for {@code /web sessions} and {@code /web logout}
   * @param hash the token's SHA-256
   * @param device such as "Safari on iPad"
   * @param admin whether the player held seasons.web.admin when last checked
   */
  record Session(
      String id,
      String hash,
      UUID player,
      String name,
      String device,
      Instant created,
      Instant lastSeen,
      Instant expires,
      boolean admin) {
    Session seen(Instant at) {
      return new Session(id, hash, player, name, device, created, at, expires, admin);
    }

    Session withAdmin(boolean next) {
      return new Session(id, hash, player, name, device, created, lastSeen, expires, next);
    }
  }

  /** A new session and the token for its cookie, which is never kept. */
  record Created(Session session, String token) {}

  private final Path file;
  private final FileWriter writer;
  private final Clock clock;
  private final Logger log;

  /** By hash. */
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();

  private Instant lastSaved = Instant.MIN;

  SessionStore(Path file, FileWriter writer, Clock clock, Logger log) {
    this.file = file;
    this.writer = writer;
    this.clock = clock;
    this.log = log;
  }

  /** Reads the file; a file that doesn't read is moved aside and everyone logs in again. */
  void load() {
    sessions.clear();
    if (!Files.exists(file)) {
      return;
    }
    try {
      JsonObject root =
          JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
      for (JsonElement e : root.getAsJsonArray("sessions")) {
        JsonObject o = e.getAsJsonObject();
        Session s =
            new Session(
                o.get("id").getAsString(),
                o.get("hash").getAsString(),
                UUID.fromString(o.get("player").getAsString()),
                o.get("name").getAsString(),
                o.get("device").getAsString(),
                Instant.parse(o.get("created").getAsString()),
                Instant.parse(o.get("lastSeen").getAsString()),
                Instant.parse(o.get("expires").getAsString()),
                o.has("admin") && o.get("admin").getAsBoolean());
        sessions.put(s.hash(), s);
      }
    } catch (IOException | RuntimeException e) {
      log.error("Can't read {}; everyone logs in again", file, e);
      sessions.clear();
      try {
        Files.move(file, file.resolveSibling("sessions.damaged-" + clock.millis() + ".json"));
      } catch (IOException moved) {
        log.error("Can't move {} aside", file, moved);
      }
    }
  }

  Created create(UUID player, String name, String device, boolean admin, Duration lifetime) {
    Instant now = clock.instant();
    String token = Tokens.secret();
    Session s =
        new Session(
            newId(player),
            Tokens.hash(token),
            player,
            name,
            device,
            now,
            now,
            now.plus(lifetime),
            admin);
    sessions.put(s.hash(), s);
    save();
    return new Created(s, token);
  }

  /** The live session for a cookie's token, its last-seen time brought up to date. */
  Optional<Session> find(String token) {
    if (token.isEmpty() || token.length() > 128) {
      return Optional.empty();
    }
    String hash = Tokens.hash(token);
    Session s = sessions.get(hash);
    if (s == null) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    if (!now.isBefore(s.expires())) {
      sessions.remove(hash);
      save();
      return Optional.empty();
    }
    Session seen = s.seen(now);
    sessions.put(hash, seen);
    if (now.isAfter(lastSaved.plus(SAVE_LAST_SEEN))) {
      save();
    }
    return Optional.of(seen);
  }

  /** A player's live sessions, the most recent first. */
  List<Session> of(UUID player) {
    Instant now = clock.instant();
    return sessions.values().stream()
        .filter(s -> s.player().equals(player) && now.isBefore(s.expires()))
        .sorted(Comparator.comparing(Session::lastSeen).reversed())
        .toList();
  }

  /** Ends one of a player's sessions by its id. */
  Optional<Session> revoke(UUID player, String id) {
    for (Session s : of(player)) {
      if (s.id().equalsIgnoreCase(id)) {
        sessions.remove(s.hash());
        save();
        return Optional.of(s);
      }
    }
    return Optional.empty();
  }

  /** Ends every session of a player; how many there were. */
  int revokeAll(UUID player) {
    List<Session> mine = of(player);
    mine.forEach(s -> sessions.remove(s.hash()));
    sessions.values().removeIf(s -> s.player().equals(player));
    save();
    return mine.size();
  }

  /** Records whether a player holds seasons.web.admin, on every session of theirs. */
  void setAdmin(UUID player, boolean admin) {
    boolean changed = false;
    for (Session s : List.copyOf(sessions.values())) {
      if (s.player().equals(player) && s.admin() != admin) {
        sessions.put(s.hash(), s.withAdmin(admin));
        changed = true;
      }
    }
    if (changed) {
      save();
    }
  }

  /** Whether a session is still live, by its id: for event streams, which check as they write. */
  boolean live(String id) {
    Instant now = clock.instant();
    return sessions.values().stream().anyMatch(s -> s.id().equals(id) && now.isBefore(s.expires()));
  }

  void save() {
    Instant now = clock.instant();
    lastSaved = now;
    sessions.values().removeIf(s -> !now.isBefore(s.expires()));
    JsonObject root = new JsonObject();
    root.addProperty("format-version", FORMAT_VERSION);
    JsonArray list = new JsonArray();
    sessions.values().stream()
        .sorted(Comparator.comparing(Session::created))
        .forEach(s -> {
          JsonObject o = new JsonObject();
          o.addProperty("id", s.id());
          o.addProperty("hash", s.hash());
          o.addProperty("player", s.player().toString());
          o.addProperty("name", s.name());
          o.addProperty("device", s.device());
          o.addProperty("created", s.created().toString());
          o.addProperty("lastSeen", s.lastSeen().toString());
          o.addProperty("expires", s.expires().toString());
          o.addProperty("admin", s.admin());
          list.add(o);
        });
    root.add("sessions", list);
    writer.replace(file, root.toString());
  }

  private String newId(UUID player) {
    List<String> taken = new ArrayList<>();
    for (Session s : sessions.values()) {
      if (s.player().equals(player)) {
        taken.add(s.id());
      }
    }
    String id;
    do {
      StringBuilder b = new StringBuilder(6);
      for (int i = 0; i < 6; i++) {
        b.append(ID_ALPHABET.charAt(Tokens.nextInt(ID_ALPHABET.length())));
      }
      id = b.toString();
    } while (taken.contains(id));
    return id;
  }

  void clear() {
    sessions.clear();
  }
}
