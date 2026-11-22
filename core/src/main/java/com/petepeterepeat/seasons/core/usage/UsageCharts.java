// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.usage;

import com.petepeterepeat.seasons.core.config.SeasonsConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Seasons' own bStats charts, beside what bStats always sends: whether Seasons Web is installed,
 * the cell assignment mode, the Hall access mode and how many seasons the server has run. Nothing
 * here names a player, a place or an address. Server owners turn bStats off for every plugin in
 * {@code plugins/bStats/config.yml}.
 */
public final class UsageCharts {
  public static final String SEASONS_WEB = "seasons_web_installed";
  public static final String CELL_ASSIGNMENT = "cell_assignment";
  public static final String HALL_ACCESS = "hall_access";
  public static final String SEASONS_RUN = "seasons_run";

  /** Every chart's id, in the order they are reported. */
  public static final List<String> CHARTS =
      List.of(SEASONS_WEB, CELL_ASSIGNMENT, HALL_ACCESS, SEASONS_RUN);

  /** From this many seasons on, the chart counts them together. */
  private static final int MANY = 10;

  private UsageCharts() {}

  /**
   * Each chart's value now.
   *
   * @param season the season on record, empty before {@code /season init}
   */
  public static Map<String, String> values(
      SeasonsConfig config, OptionalInt season, boolean webInstalled) {
    Map<String, String> values = new LinkedHashMap<>();
    values.put(SEASONS_WEB, webInstalled ? "yes" : "no");
    values.put(CELL_ASSIGNMENT, dashed(config.cells().assignment()));
    values.put(HALL_ACCESS, dashed(config.cells().access()));
    values.put(SEASONS_RUN, seasonsRun(season));
    return values;
  }

  /**
   * The season on record, which counts the seasons a server has run, the ones before Seasons was
   * installed included (questions 290): "none" before the first is recorded, then each number up to
   * 9, then "10+".
   */
  static String seasonsRun(OptionalInt season) {
    if (season.isEmpty()) {
      return "none";
    }
    return season.getAsInt() >= MANY ? MANY + "+" : Integer.toString(season.getAsInt());
  }

  /** A mode as config files write it, such as {@code random}. */
  private static String dashed(Enum<?> mode) {
    return mode.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
