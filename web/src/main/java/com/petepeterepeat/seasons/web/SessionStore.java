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
 *
 * <p>Requests look sessions up on their own threads while {@code /web logout} and the admin check
 * change them on the server thread, so every change to one session is a single atomic map
 * operation: a lookup never puts back a session that was ended, or an admin flag that was taken
 * away. Saves take their snapshot and queue it under one lock, so the file is written in the order
 * the snapshots were taken and the last write always holds every change before it.
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
   * @param grants the parts of seasons.web.admin the player held when last checked
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
      Grants grants) {
    Session seen(Instant at) {
      return new Session(id, hash, player, name, device, created, at, expires, grants);
    }

    Session withGrants(Grants next) {
      return new Session(id, hash, player, name, device, created, lastSeen, expires, next);
    }

    /** Whether the player held any part of seasons.web.admin. */
    boolean admin() {
      return grants.any();
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

  private final Object saving = new Object();
  private volatile Instant lastSaved = Instant.MIN;

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
                grants(o));
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

  /**
   * A session's grants: its {@code grants} list (1.5), or its {@code admin} flag as 1.4 wrote it,
   * which was all of them.
   */
  private static Grants grants(JsonObject o) {
    if (o.get("grants") instanceof JsonArray list) {
      List<Grants.Grant> held = new ArrayList<>();
      for (JsonElement e : list) {
        Grants.Grant.parse(e.getAsString()).ifPresent(held::add);
      }
      return Grants.of(held);
    }
    return Grants.of(o.has("admin") && o.get("admin").getAsBoolean());
  }

  Created create(UUID player, String name, String device, boolean admin, Duration lifetime) {
    return create(player, name, device, Grants.of(admin), lifetime);
  }

  Created create(UUID player, String name, String device, Grants grants, Duration lifetime) {
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
            grants);
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
    Instant now = clock.instant();
    boolean[] expired = {false};
    // One atomic step: if the session was ended meanwhile it stays ended.
    Session seen =
        sessions.computeIfPresent(
            hash,
            (k, s) -> {
              if (!now.isBefore(s.expires())) {
                expired[0] = true;
                return null;
              }
              return s.seen(now);
            });
    if (expired[0]) {
      save();
      return Optional.empty();
    }
    if (seen == null) {
      return Optional.empty();
    }
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

  /** Records whether a player holds seasons.web.admin, all of it or none, on their sessions. */
  void setAdmin(UUID player, boolean admin) {
    setGrants(player, Grants.of(admin));
  }

  /** Records the parts of seasons.web.admin a player holds, on every session of theirs. */
  void setGrants(UUID player, Grants grants) {
    boolean[] changed = {false};
    for (Session s : List.copyOf(sessions.values())) {
      if (s.player().equals(player)) {
        // Atomic with lookups, which could otherwise put back the flag as it was.
        sessions.computeIfPresent(
            s.hash(),
            (k, now) -> {
              if (now.grants().equals(grants)) {
                return now;
              }
              changed[0] = true;
              return now.withGrants(grants);
            });
      }
    }
    if (changed[0]) {
      save();
    }
  }

  /** Whether a session is still live, by its id: for event streams, which check as they write. */
  boolean live(String id) {
    Instant now = clock.instant();
    return sessions.values().stream().anyMatch(s -> s.id().equals(id) && now.isBefore(s.expires()));
  }

  void save() {
    // The snapshot and its place in the writer's queue are taken together, so a snapshot taken
    // before a logout is never written after the one taken after it.
    synchronized (saving) {
      writeSnapshot();
    }
  }

  private void writeSnapshot() {
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
          // admin as 1.4 reads it; grants, the parts held, from 1.5.
          o.addProperty("admin", s.admin());
          JsonArray grants = new JsonArray();
          s.grants().held().stream()
              .sorted()
              .forEach(g -> grants.add(g.name().toLowerCase(java.util.Locale.ROOT)));
          o.add("grants", grants);
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
