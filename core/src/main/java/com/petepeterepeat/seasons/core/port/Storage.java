// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.nio.file.Path;

/** Where Seasons keeps its files. */
public interface Storage {
  /** Human-owned settings: config.yml, messages.yml, secrets.yml. */
  Path configDirectory();

  /** World-specific files, inside the main world folder so a world backup carries them. */
  Path worldDataDirectory();

  /** Stops Seasons on this server, as {@code /season reset} does once its files are gone. */
  default void stopSeasons() {}
}
