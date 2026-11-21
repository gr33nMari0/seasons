// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import com.petepeterepeat.seasons.core.hall.Gallery;
import com.petepeterepeat.seasons.core.hall.HallLayout;
import com.petepeterepeat.seasons.core.map.Waypoints;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Every file's contents at one moment. Reload builds a new snapshot and swaps it in whole, so code
 * never sees half of one reload and half of another.
 *
 * @param seasons season files by season number
 * @param galleries gallery files by gallery id
 * @param galleriesLeftOut gallery files that couldn't be loaded at startup and were left out, by
 *     gallery id, with their problems. A reload never leaves anything out: it swaps in every file
 *     or none, so a successful one clears this.
 * @param waypoints the shared waypoints, from markers.yml
 * @param markersLeftOut markers.yml's problems if it couldn't be loaded at startup and was left
 *     out, so the waypoints are none; empty otherwise. As with galleries, a reload never leaves it
 *     out.
 * @param settings every setting config.yml has, by key path such as {@code hof.entry.cooldown},
 *     with its value in force as the file (or the bundled default) writes it; only keys the bundled
 *     file lists, so nothing else in config.yml is ever shown
 * @param hall the hub, the Hall spawn, the Hall's exits and links, from hall.yml
 * @param hallLeftOut hall.yml's problems if it couldn't be loaded at startup and was left out, so
 *     the Hall has none of its own parts; empty otherwise (questions 243)
 */
public record ConfigSnapshot(
    SeasonsConfig config,
    Messages messages,
    Secrets secrets,
    Map<Integer, SeasonFile> seasons,
    Map<String, Gallery> galleries,
    Map<String, List<ConfigProblem>> galleriesLeftOut,
    Waypoints waypoints,
    List<ConfigProblem> markersLeftOut,
    Map<String, String> settings,
    HallLayout hall,
    List<ConfigProblem> hallLeftOut) {
  public ConfigSnapshot {
    hallLeftOut = List.copyOf(hallLeftOut);
    seasons = Map.copyOf(seasons);
    galleries = java.util.Collections.unmodifiableMap(new TreeMap<>(galleries));
    galleriesLeftOut = java.util.Collections.unmodifiableMap(new TreeMap<>(galleriesLeftOut));
    markersLeftOut = List.copyOf(markersLeftOut);
    settings = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(settings));
  }

  public ConfigSnapshot(
      SeasonsConfig config,
      Messages messages,
      Secrets secrets,
      Map<Integer, SeasonFile> seasons,
      Map<String, Gallery> galleries,
      Map<String, List<ConfigProblem>> galleriesLeftOut,
      Waypoints waypoints,
      List<ConfigProblem> markersLeftOut,
      Map<String, String> settings) {
    this(
        config,
        messages,
        secrets,
        seasons,
        galleries,
        galleriesLeftOut,
        waypoints,
        markersLeftOut,
        settings,
        HallLayout.NONE,
        List.of());
  }

  public ConfigSnapshot(
      SeasonsConfig config,
      Messages messages,
      Secrets secrets,
      Map<Integer, SeasonFile> seasons,
      Map<String, Gallery> galleries,
      Map<String, List<ConfigProblem>> galleriesLeftOut,
      Waypoints waypoints,
      List<ConfigProblem> markersLeftOut) {
    this(
        config,
        messages,
        secrets,
        seasons,
        galleries,
        galleriesLeftOut,
        waypoints,
        markersLeftOut,
        Map.of());
  }

  public ConfigSnapshot(
      SeasonsConfig config,
      Messages messages,
      Secrets secrets,
      Map<Integer, SeasonFile> seasons,
      Map<String, Gallery> galleries,
      Map<String, List<ConfigProblem>> galleriesLeftOut,
      Waypoints waypoints) {
    this(config, messages, secrets, seasons, galleries, galleriesLeftOut, waypoints, List.of());
  }

  public ConfigSnapshot(
      SeasonsConfig config,
      Messages messages,
      Secrets secrets,
      Map<Integer, SeasonFile> seasons,
      Map<String, Gallery> galleries,
      Map<String, List<ConfigProblem>> galleriesLeftOut) {
    this(config, messages, secrets, seasons, galleries, galleriesLeftOut, Waypoints.NONE);
  }

  public ConfigSnapshot(
      SeasonsConfig config,
      Messages messages,
      Secrets secrets,
      Map<Integer, SeasonFile> seasons,
      Map<String, Gallery> galleries) {
    this(config, messages, secrets, seasons, galleries, Map.of());
  }

  public ConfigSnapshot(
      SeasonsConfig config, Messages messages, Secrets secrets, Map<Integer, SeasonFile> seasons) {
    this(config, messages, secrets, seasons, Map.of());
  }

  /**
   * Whether any gallery file, or hall.yml, was left out at startup: the Hall is missing a part, so
   * the season can't end or launch (questions 61 and 243).
   */
  public boolean anyGalleryLeftOut() {
    return !galleriesLeftOut.isEmpty() || !hallLeftOut.isEmpty();
  }

  /** Whether a position is in the Hall: inside the hub or any gallery's bounds. */
  public boolean inHall(Position position) {
    return hall.inHub(position) || galleries.values().stream().anyMatch(g -> g.contains(position));
  }

  /** The same files with hall.yml's parts replaced, as a {@code /hof hall} command leaves them. */
  public ConfigSnapshot withHall(HallLayout next) {
    return new ConfigSnapshot(
        config,
        messages,
        secrets,
        seasons,
        galleries,
        galleriesLeftOut,
        waypoints,
        markersLeftOut,
        settings,
        next,
        hallLeftOut);
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
    return new ConfigSnapshot(
        config,
        messages,
        secrets,
        seasons,
        next,
        galleriesLeftOut,
        waypoints,
        markersLeftOut,
        settings,
        hall,
        hallLeftOut);
  }
}
