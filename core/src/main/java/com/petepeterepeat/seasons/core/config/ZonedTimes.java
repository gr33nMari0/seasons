// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Scheduled times: ISO-8601 and always with a time zone, so nobody guesses the server's. */
public final class ZonedTimes {
  private ZonedTimes() {}

  /**
   * Parses a time such as {@code 2026-12-05T20:00-05:00}, {@code 2026-12-06T01:00Z} or {@code
   * 2026-12-05T20:00-05:00[America/New_York]}.
   *
   * @throws IllegalArgumentException if it doesn't parse or has no time zone
   */
  public static ZonedDateTime parse(String text) {
    try {
      return ZonedDateTime.parse(text.trim(), DateTimeFormatter.ISO_ZONED_DATE_TIME);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException(
          "'"
              + text
              + "' is not an ISO time with a time zone, such as 2026-12-05T20:00-05:00 or"
              + " 2026-12-06T01:00Z");
    }
  }

  public static String format(ZonedDateTime time) {
    return DateTimeFormatter.ISO_ZONED_DATE_TIME.format(time);
  }
}
