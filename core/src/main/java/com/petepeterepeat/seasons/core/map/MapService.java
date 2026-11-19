// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.map;

import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.config.ConfigSnapshot;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.ScheduledTask;
import com.petepeterepeat.seasons.core.season.SeasonState;
import com.petepeterepeat.seasons.core.zone.Zones;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the published map in step with the files and the season state. Once a second, on the server
 * thread, it checks whether either is a different object from the last time: every reload, every
 * command edit and every change to the state makes a new one, which {@link Zones} relies on too.
 * Only then does it build the model again, and it publishes the model only if it shows something
 * different. So every change reaches the map within a second (launch, Homecoming, one zone
 * unlocked, a candidate or waypoint added, a hand edit reloaded) at the cost of two comparisons a
 * second the rest of the time.
 */
public final class MapService {
  /** How often the files and the state are checked for a change. */
  static final Duration CHECK = Duration.ofSeconds(1);

  private final Platform platform;
  private final ConfigHolder config;
  private final Supplier<Optional<SeasonState>> state;
  private final Zones zones;
  private final MapPublisher publisher;

  private @Nullable ConfigSnapshot builtFrom;
  private @Nullable SeasonState builtFor;
  private @Nullable MapModel published;
  private @Nullable ScheduledTask task;

  public MapService(
      Platform platform,
      ConfigHolder config,
      Supplier<Optional<SeasonState>> state,
      Zones zones,
      MapPublisher publisher) {
    this.platform = platform;
    this.config = config;
    this.state = state;
    this.zones = zones;
    this.publisher = publisher;
  }

  /** Publishes the map now, and checks for changes once a second from then on. */
  public void start() {
    refresh();
    task = platform.scheduler().runGlobalRepeating(CHECK, this::refresh);
  }

  public void stop() {
    if (task != null) {
      task.cancel();
      task = null;
    }
  }

  /** Builds and publishes the model again if the files or the state have changed. */
  @SuppressWarnings("ReferenceEquality")
  void refresh() {
    ConfigSnapshot snapshot = config.get();
    Optional<SeasonState> current = state.get();
    if (snapshot == builtFrom && current.orElse(null) == builtFor) {
      return;
    }
    builtFrom = snapshot;
    builtFor = current.orElse(null);
    MapModel model =
        MapModels.build(snapshot, current, zones.inForce(), zones::until, platform.world());
    if (!model.equals(published)) {
      published = model;
      publisher.publish(model);
    }
  }
}
