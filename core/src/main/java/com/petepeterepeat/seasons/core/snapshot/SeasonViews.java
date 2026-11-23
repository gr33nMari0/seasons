// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.snapshot;

import com.petepeterepeat.seasons.api.SeasonView;
import com.petepeterepeat.seasons.core.season.Phase;
import com.petepeterepeat.seasons.core.season.SeasonState;
import com.petepeterepeat.seasons.core.season.Stage;
import com.petepeterepeat.seasons.core.zone.UnlockCondition;
import com.petepeterepeat.seasons.core.zone.ZoneMode;
import com.petepeterepeat.seasons.core.zone.Zones;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The season state as Seasons API shows it. */
public final class SeasonViews {
  private SeasonViews() {}

  public static SeasonView of(SeasonState state, List<Zones.InForce> zones) {
    return of(state, zones, Optional.empty());
  }

  /**
   * The season state as Seasons API shows it, with when Homecoming is planned.
   *
   * @param homecoming the season file's condition for Homecoming, if it has a file
   */
  public static SeasonView of(
      SeasonState state, List<Zones.InForce> zones, Optional<UnlockCondition> homecoming) {
    Phase phase = state.phase();
    return new SeasonView(
        state.season(),
        phase(phase),
        stage(phase).map(SeasonViews::text),
        state.plannedEnd().map(ZonedDateTime::toInstant),
        phase instanceof Phase.Countdown c ? Optional.of(c.endsAt()) : Optional.empty(),
        state.launchedAt(),
        state.homecomingAt(),
        zones.stream()
            .map(z -> new SeasonView.ZoneView(z.zone().displayName(), mode(z.now())))
            .toList(),
        homecoming.flatMap(
            h -> ApiReference.plannedHomecoming(h, state.launchedAt(), state.homecomingAt())));
  }

  /** The phase's name in the API. */
  public static String phase(Phase phase) {
    return switch (phase) {
      case Phase.Active a -> "active";
      case Phase.Countdown c -> "countdown";
      case Phase.Archiving a -> "archiving";
      case Phase.Intermission i -> "intermission";
    };
  }

  /** The stage, while the season is active or counting down to its end. */
  public static Optional<Stage> stage(Phase phase) {
    return switch (phase) {
      case Phase.Active a -> Optional.of(a.stage());
      case Phase.Countdown c -> Optional.of(c.stageBefore());
      default -> Optional.empty();
    };
  }

  static String text(Stage stage) {
    return stage.name().toLowerCase(Locale.ROOT);
  }

  static String mode(ZoneMode mode) {
    return mode.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
