// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Durations as config files write them: {@code 7d}, {@code 1h30m}, {@code 10s}, or {@code 0}. */
public final class Durations {
  private static final Pattern PART = Pattern.compile("(\\d+)([dhms])");

  private Durations() {}

  /**
   * Parses a duration such as {@code 7d} or {@code 1h30m}.
   *
   * @throws IllegalArgumentException with a message fit for an admin
   */
  public static Duration parse(String text) {
    String trimmed = text.trim();
    if (trimmed.equals("0")) {
      return Duration.ZERO;
    }
    Matcher matcher = PART.matcher(trimmed);
    Duration total = Duration.ZERO;
    int end = 0;
    while (matcher.find()) {
      if (matcher.start() != end) {
        break;
      }
      long amount = Long.parseLong(matcher.group(1));
      total =
          total.plus(
              switch (matcher.group(2)) {
                case "d" -> Duration.ofDays(amount);
                case "h" -> Duration.ofHours(amount);
                case "m" -> Duration.ofMinutes(amount);
                default -> Duration.ofSeconds(amount);
              });
      end = matcher.end();
    }
    if (end == 0 || end != trimmed.length()) {
      throw new IllegalArgumentException(
          "'" + text + "' is not a duration; write it like 7d, 1h30m, 10m or 10s");
    }
    return total;
  }

  /** The shortest form {@link #parse} reads back, such as {@code 1h30m}. */
  public static String format(Duration duration) {
    if (duration.isZero()) {
      return "0";
    }
    StringBuilder out = new StringBuilder();
    long seconds = duration.toSeconds();
    long[] sizes = {86_400, 3_600, 60, 1};
    String[] units = {"d", "h", "m", "s"};
    for (int i = 0; i < sizes.length; i++) {
      if (seconds >= sizes[i]) {
        out.append(seconds / sizes[i]).append(units[i]);
        seconds %= sizes[i];
      }
    }
    return out.toString();
  }
}
