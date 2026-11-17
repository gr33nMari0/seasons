// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import com.petepeterepeat.seasons.core.message.Messages;
import java.util.Map;
import java.util.Optional;

/**
 * Every file's contents at one moment. Reload builds a new snapshot and swaps it in whole, so code
 * never sees half of one reload and half of another.
 *
 * @param seasons season files by season number
 */
public record ConfigSnapshot(
    SeasonsConfig config, Messages messages, Secrets secrets, Map<Integer, SeasonFile> seasons) {
  public ConfigSnapshot {
    seasons = Map.copyOf(seasons);
  }

  public Optional<SeasonFile> season(int season) {
    return Optional.ofNullable(seasons.get(season));
  }
}
