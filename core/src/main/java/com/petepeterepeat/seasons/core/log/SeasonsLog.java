// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.log;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seasons' server log. Every line goes through the one logger named {@value #NAME}, the plugin's
 * own name, so the console labels it {@code [Seasons]} rather than with a Java class name.
 *
 * <p>INFO is for the startup summary and the season's milestones, WARN for what an admin should act
 * on, ERROR for what failed. Debug lines are for the areas listed in {@code logging.debug} only,
 * and go out at INFO with a {@code [debug <area>]} prefix, since servers hide the DEBUG level.
 */
public final class SeasonsLog {
  /** The logger's name, which is the plugin's. */
  public static final String NAME = "Seasons";

  private static final Logger LOG = LoggerFactory.getLogger(NAME);

  /** The areas with debug lines on; replaced as a whole whenever the config loads. */
  private static volatile Set<DebugArea> debugging = Set.of();

  private SeasonsLog() {}

  /** The logger every Seasons class writes to. */
  public static Logger get() {
    return LOG;
  }

  /** Switches debug lines on for exactly {@code areas}. */
  public static void debugging(Set<DebugArea> areas) {
    debugging = areas.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(areas));
  }

  /** Whether {@code area}'s debug lines are on. */
  public static boolean debugging(DebugArea area) {
    return debugging.contains(area);
  }

  /** A debug line for {@code area}, written only while that area is on. */
  public static void debug(DebugArea area, String format, Object... arguments) {
    if (debugging(area)) {
      LOG.info("[debug " + area.name().toLowerCase(Locale.ROOT) + "] " + format, arguments);
    }
  }
}
