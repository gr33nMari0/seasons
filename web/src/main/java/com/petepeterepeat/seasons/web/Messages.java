// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * {@code plugins/SeasonsWeb/messages.yml}: every line players see, by dotted key, in MiniMessage.
 * Placeholders are always filled in as plain text, never parsed.
 */
final class Messages {
  private final Map<String, String> lines;

  private Messages(Map<String, String> lines) {
    this.lines = Map.copyOf(lines);
  }

  static Messages load(Path folder) throws ConfigurateException {
    Map<String, String> lines = new HashMap<>();
    flatten("", SettingsFile.loadMerged(folder, SettingsFile.MESSAGES), lines);
    return new Messages(lines);
  }

  private static void flatten(String prefix, ConfigurationNode node, Map<String, String> into) {
    if (node.isMap()) {
      node.childrenMap()
          .forEach((key, child) ->
              flatten(prefix.isEmpty() ? key.toString() : prefix + "." + key, child, into));
    } else if (node.rawScalar() instanceof String text) {
      into.put(prefix, text);
    }
  }

  /**
   * A plain-text piece placed inside another line, such as a device's name or "10 min ago": the
   * key's text with each {@code <name>} replaced by its value. Never parsed as formatting, so these
   * pieces carry no tags.
   *
   * @param pairs placeholder names and values, alternating
   */
  String text(String key, String... pairs) {
    String out = get(key);
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      out = out.replace("<" + pairs[i] + ">", pairs[i + 1]);
    }
    return out;
  }

  /** Every line under a key, by the rest of its key, such as {@code feed.join} under dashboard. */
  Map<String, String> section(String prefix) {
    Map<String, String> out = new java.util.TreeMap<>();
    lines.forEach((k, v) -> {
      if (k.startsWith(prefix + ".")) {
        out.put(k.substring(prefix.length() + 1), v);
      }
    });
    return out;
  }

  /** The line for a key, or the key itself if messages.yml lost it. */
  String get(String key) {
    return lines.getOrDefault(key, key);
  }
}
