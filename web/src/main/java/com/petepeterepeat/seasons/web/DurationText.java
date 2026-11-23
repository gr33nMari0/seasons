// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Durations as web.yml writes them: a number and a unit, such as {@code 30d}, {@code 5m}. */
final class DurationText {
  private static final Pattern FORM = Pattern.compile("(\\d{1,9})\\s*(ms|s|m|h|d)");

  private DurationText() {}

  static Duration parse(String text) {
    Matcher m = FORM.matcher(text.strip().toLowerCase(java.util.Locale.ROOT));
    if (!m.matches()) {
      throw new IllegalArgumentException(
          "\"" + text + "\" isn't a duration such as 30d, 12h, 5m or 30s");
    }
    long n = Long.parseLong(m.group(1));
    return switch (m.group(2)) {
      case "ms" -> Duration.ofMillis(n);
      case "s" -> Duration.ofSeconds(n);
      case "m" -> Duration.ofMinutes(n);
      case "h" -> Duration.ofHours(n);
      default -> Duration.ofDays(n);
    };
  }

  static String format(Duration d) {
    long s = d.toSeconds();
    if (d.toMillis() % 1000 != 0) {
      return d.toMillis() + "ms";
    }
    if (s != 0 && s % 86_400 == 0) {
      return s / 86_400 + "d";
    }
    if (s != 0 && s % 3600 == 0) {
      return s / 3600 + "h";
    }
    if (s != 0 && s % 60 == 0) {
      return s / 60 + "m";
    }
    return s + "s";
  }
}
