// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.api;

import java.util.OptionalInt;

/** Read-only queries about the running Seasons plugin. Every method is safe from any thread. */
public interface SeasonsApi {

  /**
   * The Seasons plugin version.
   *
   * @return the version, such as {@code 1.0.0}
   */
  String version();

  /**
   * The season in progress.
   *
   * @return its number, or empty on a server that has never recorded a season
   */
  OptionalInt currentSeason();
}
