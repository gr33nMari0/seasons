// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * web.yml as written, against the web.yml this version bundles (M10 plan, O2 and O3): the settings
 * it lacks, the ones it has that this version doesn't know, and the values still waiting for the
 * admin. Settings whose children are the admin's own names, such as {@code screens}, are compared
 * as a whole.
 */
final class SettingsAudit {
  /** What {@code /web upgrade} writes where only the admin knows the value. */
  static final String PLACEHOLDER = "[fill me]";

  /** Settings whose keys are the admin's own: never missing or unknown inside. */
  static final Set<String> FREE_FORM = Set.of("visibility", "screens");

  /** Settings {@code /web upgrade} adds as {@link #PLACEHOLDER} rather than the default. */
  static final Set<String> FILL_IN = Set.of("public-url");

  /** A setting this version doesn't know, and the one it was probably meant to be. */
  record Unknown(String path, Optional<String> meant) {}

  private SettingsAudit() {}

  /** The dotted paths of the bundled settings the file lacks: a whole section once, not its keys. */
  static List<String> missing(ConfigurationNode written, ConfigurationNode bundled) {
    List<String> out = new ArrayList<>();
    missing(written, bundled, "", out);
    return out;
  }

  private static void missing(
      ConfigurationNode written, ConfigurationNode bundled, String prefix, List<String> out) {
    for (Map.Entry<Object, ? extends ConfigurationNode> e : bundled.childrenMap().entrySet()) {
      String key = String.valueOf(e.getKey());
      String path = prefix.isEmpty() ? key : prefix + "." + key;
      ConfigurationNode mine = written.node(e.getKey());
      if (mine.virtual()) {
        out.add(path);
      } else if (e.getValue().isMap() && mine.isMap() && !FREE_FORM.contains(path)) {
        missing(mine, e.getValue(), path, out);
      }
    }
  }

  /** The settings the file has that the bundled web.yml doesn't. */
  static List<Unknown> unknown(ConfigurationNode written, ConfigurationNode bundled) {
    List<Unknown> out = new ArrayList<>();
    unknown(written, bundled, "", out);
    return out;
  }

  private static void unknown(
      ConfigurationNode written, ConfigurationNode bundled, String prefix, List<Unknown> out) {
    if (!written.isMap()) {
      return;
    }
    for (Map.Entry<Object, ? extends ConfigurationNode> e : written.childrenMap().entrySet()) {
      String key = String.valueOf(e.getKey());
      String path = prefix.isEmpty() ? key : prefix + "." + key;
      ConfigurationNode known = bundled.node(e.getKey());
      if (known.virtual()) {
        List<String> siblings =
            bundled.childrenMap().keySet().stream().map(String::valueOf).toList();
        Optional<String> meant =
            closest(key, siblings).map(k -> prefix.isEmpty() ? k : prefix + "." + k);
        out.add(new Unknown(path, meant));
      } else if (known.isMap() && !FREE_FORM.contains(path)) {
        unknown(e.getValue(), known, path, out);
      }
    }
  }

  /** Values still {@link #PLACEHOLDER}, by path. */
  static List<String> placeholders(ConfigurationNode written) {
    List<String> out = new ArrayList<>();
    SettingsFile.flatten(written)
        .forEach((path, value) -> {
          if (PLACEHOLDER.equals(value.strip())) {
            out.add(path);
          }
        });
    return out;
  }

  /** The known key nearest a mistyped one, within two edits, if one is. */
  static Optional<String> closest(String typed, List<String> known) {
    String best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (String k : known) {
      int d = distance(typed.toLowerCase(java.util.Locale.ROOT), k);
      if (d < bestDistance) {
        bestDistance = d;
        best = k;
      }
    }
    return best != null && bestDistance <= 2 ? Optional.of(best) : Optional.empty();
  }

  /** Levenshtein distance. */
  static int distance(String a, String b) {
    int[] previous = new int[b.length() + 1];
    int[] current = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) {
      previous[j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      current[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        current[j] =
            Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
      }
      int[] swap = previous;
      previous = current;
      current = swap;
    }
    return previous[b.length()];
  }
}
