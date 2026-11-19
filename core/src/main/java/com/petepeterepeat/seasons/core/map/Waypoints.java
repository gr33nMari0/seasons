// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.map;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * {@code <world>/seasons/markers.yml}: the shared waypoints, in sets. Each set is a marker set of
 * its own on the map, such as {@code megaprojects}. Within a set, names are told apart ignoring
 * case.
 *
 * @param sets each set's waypoints by set id, in order of their names ignoring case
 */
public record Waypoints(Map<String, List<Waypoint>> sets) {
  /** No waypoints at all. */
  public static final Waypoints NONE = new Waypoints(Map.of());

  /** A set's id: lower-case letters, digits and dashes, such as {@code megaprojects}. */
  public static final Pattern SET = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");

  /** The longest a waypoint's name may be. */
  public static final int MAX_NAME = 64;

  public Waypoints {
    Map<String, List<Waypoint>> sorted = new TreeMap<>();
    sets.forEach((set, waypoints) -> {
      List<Waypoint> named = new ArrayList<>(waypoints);
      named.sort(Comparator.comparing(w -> key(w.name())));
      sorted.put(set, List.copyOf(named));
    });
    sets = Collections.unmodifiableMap(sorted);
  }

  /** Whether a name may name a waypoint: 1 to {@link #MAX_NAME} characters, not all spaces. */
  public static boolean validName(String name) {
    return !name.isBlank() && name.length() <= MAX_NAME && name.equals(name.strip());
  }

  /** The waypoint in a set with a name, ignoring case. */
  public Optional<Waypoint> find(String set, String name) {
    return sets.getOrDefault(set, List.of()).stream()
        .filter(w -> key(w.name()).equals(key(name)))
        .findFirst();
  }

  /** How waypoint names are compared: ignoring case. */
  public static String key(String name) {
    return name.toLowerCase(Locale.ROOT);
  }
}
