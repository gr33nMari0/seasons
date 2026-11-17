// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.season;

import com.petepeterepeat.seasons.core.message.Messages;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Times and durations as players read them, with unit words from messages.yml. */
public final class HumanTime {
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm z", Locale.ROOT);
  private static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'", Locale.ROOT);

  private HumanTime() {}

  /** A duration in its largest whole unit, such as "7 days", "1 hour" or "90 minutes". */
  public static String duration(Messages messages, Duration duration) {
    long seconds = duration.toSeconds();
    String unit;
    long amount;
    if (seconds > 0 && seconds % 86_400 == 0) {
      unit = "day";
      amount = seconds / 86_400;
    } else if (seconds > 0 && seconds % 3_600 == 0) {
      unit = "hour";
      amount = seconds / 3_600;
    } else if (seconds > 0 && seconds % 60 == 0) {
      unit = "minute";
      amount = seconds / 60;
    } else {
      unit = "second";
      amount = seconds;
    }
    String key = "time." + unit + (amount == 1 ? "" : "s");
    return messages.text(key, "n", Long.toString(amount));
  }

  /** A countdown clock: "9:59", or "1:00:00" for an hour or more. */
  public static String clock(Duration remaining) {
    long seconds = Math.max(0, (remaining.toMillis() + 999) / 1000);
    long hours = seconds / 3_600;
    long minutes = seconds % 3_600 / 60;
    long secs = seconds % 60;
    return hours > 0
        ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
        : String.format(Locale.ROOT, "%d:%02d", minutes, secs);
  }

  /** A time in its own zone, such as "2026-12-05 20:00 EST", or "... UTC". */
  public static String time(ZonedDateTime time) {
    return time.getZone().normalized().equals(ZoneOffset.UTC)
        ? UTC.format(time)
        : TIME.format(time);
  }

  /** A time in another zone, such as the server's or UTC. */
  public static String time(ZonedDateTime time, ZoneId zone) {
    return time(time.withZoneSameInstant(zone));
  }
}
