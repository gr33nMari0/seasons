// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import com.petepeterepeat.seasons.core.hall.Gallery;
import com.petepeterepeat.seasons.core.message.Messages;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Every file's contents at one moment. Reload builds a new snapshot and swaps it in whole, so code
 * never sees half of one reload and half of another.
 *
 * @param seasons season files by season number
 * @param galleries gallery files by gallery id
 */
public record ConfigSnapshot(
    SeasonsConfig config,
    Messages messages,
    Secrets secrets,
    Map<Integer, SeasonFile> seasons,
    Map<String, Gallery> galleries) {
  public ConfigSnapshot {
    seasons = Map.copyOf(seasons);
    galleries = java.util.Collections.unmodifiableMap(new TreeMap<>(galleries));
  }

  public ConfigSnapshot(
      SeasonsConfig config, Messages messages, Secrets secrets, Map<Integer, SeasonFile> seasons) {
    this(config, messages, secrets, seasons, Map.of());
  }

  public Optional<SeasonFile> season(int season) {
    return Optional.ofNullable(seasons.get(season));
  }

  public Optional<Gallery> gallery(String id) {
    return Optional.ofNullable(galleries.get(id));
  }

  /** Every gallery, by id. */
  public Collection<Gallery> allGalleries() {
    return galleries.values();
  }

  /** The same files with one gallery added or replaced, as a {@code /hof} command leaves them. */
  public ConfigSnapshot withGallery(Gallery gallery) {
    Map<String, Gallery> next = new TreeMap<>(galleries);
    next.put(gallery.id(), gallery);
    return new ConfigSnapshot(config, messages, secrets, seasons, next);
  }
}
