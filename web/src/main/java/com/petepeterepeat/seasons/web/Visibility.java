// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Who sees each widget and field, from web.yml's {@code visibility} over the defaults of the spec's
 * access levels. A field such as {@code online.region} is seen only by those who also see its
 * widget, {@code online}.
 */
final class Visibility {
  /** A visibility level, least to most restricted. */
  enum Level {
    PUBLIC,
    PLAYERS,
    ADMINS,
    HIDDEN;

    static Optional<Level> parse(String text) {
      for (Level level : values()) {
        if (level.name().equalsIgnoreCase(text.strip())) {
          return Optional.of(level);
        }
      }
      return Optional.empty();
    }
  }

  /** Every key and its default, in the order the dashboard lists them. */
  static final Map<String, Level> DEFAULTS;

  /** The widgets, which the connect page lists as features. */
  static final List<String> WIDGETS =
      List.of(
          "season",
          "online",
          "event-feed",
          "map",
          "hall",
          "museum",
          "history",
          "leaderboards",
          "profiles",
          "waypoints",
          "converter",
          "links",
          "server-health",
          "pending-players",
          "audit-log");

  static {
    Map<String, Level> d = new LinkedHashMap<>();
    d.put("season", Level.PUBLIC);
    d.put("online", Level.PUBLIC);
    d.put("online.dimension", Level.PUBLIC);
    d.put("online.region", Level.PUBLIC);
    d.put("online.afk", Level.PUBLIC);
    d.put("event-feed", Level.PUBLIC);
    d.put("map", Level.PUBLIC);
    d.put("hall", Level.PUBLIC);
    d.put("hall.gear", Level.PLAYERS);
    d.put("museum", Level.PUBLIC);
    d.put("history", Level.PUBLIC);
    d.put("waypoints", Level.PUBLIC);
    d.put("converter", Level.PUBLIC);
    d.put("links", Level.PUBLIC);
    d.put("leaderboards", Level.PLAYERS);
    d.put("profiles.summary", Level.PUBLIC);
    d.put("profiles", Level.PLAYERS);
    d.put("server-health", Level.ADMINS);
    d.put("pending-players", Level.ADMINS);
    d.put("audit-log", Level.ADMINS);
    DEFAULTS = java.util.Collections.unmodifiableMap(d);
  }

  private final Map<String, Level> levels;
  private final boolean open;

  /**
   * Checks web.yml's visibility.
   *
   * @param open web.yml's {@code public}: false raises every public key to players
   * @param problems where unknown keys and levels are reported
   */
  Visibility(Map<String, String> configured, boolean open, List<String> problems) {
    Map<String, Level> merged = new LinkedHashMap<>(DEFAULTS);
    configured.forEach((key, value) -> {
      String k = key.strip().toLowerCase(Locale.ROOT);
      if (!DEFAULTS.containsKey(k)) {
        problems.add("visibility: no widget or field \"" + key + "\"; it is left out");
        return;
      }
      Optional<Level> level = Level.parse(String.valueOf(value));
      if (level.isEmpty()) {
        problems.add(
            "visibility: " + key + " is \"" + value + "\", not public, players, admins or hidden");
        return;
      }
      merged.put(k, level.get());
    });
    this.levels = Map.copyOf(merged);
    this.open = open;
  }

  /** The level a key needs, with its widget's when stricter, and public raised when closed. */
  Level level(String key) {
    Level own = levels.getOrDefault(key, Level.HIDDEN);
    int dot = key.indexOf('.');
    // A profile's summary is the public part of a profile, not a field of the full one.
    if (dot > 0 && !key.equals("profiles.summary")) {
      Level widget = levels.getOrDefault(key.substring(0, dot), Level.PUBLIC);
      if (widget.compareTo(own) > 0) {
        own = widget;
      }
    }
    return !open && own == Level.PUBLIC ? Level.PLAYERS : own;
  }

  /** Whether a viewer sees a key. */
  boolean allows(String key, Viewer viewer) {
    return switch (level(key)) {
      case PUBLIC -> true;
      case PLAYERS -> !(viewer instanceof Viewer.Anonymous);
      case ADMINS -> viewer instanceof Viewer.Player p && p.grants().sees(key);
      case HIDDEN -> false;
    };
  }

  /** Whether a key is shown to anyone. */
  boolean shown(String key) {
    return level(key) != Level.HIDDEN;
  }

  boolean open() {
    return open;
  }
}
