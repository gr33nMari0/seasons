// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.api.PlayerName;
import com.petepeterepeat.seasons.api.SeasonsApi;
import com.petepeterepeat.seasons.api.event.SeasonsEvent;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.season.DragonState;
import com.petepeterepeat.seasons.core.season.Phase;
import com.petepeterepeat.seasons.core.season.SeasonFirst;
import com.petepeterepeat.seasons.core.season.SeasonState;
import com.petepeterepeat.seasons.core.season.Stage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApiEventsTest {
  private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");
  private final PlayerId alex = new PlayerId(UUID.randomUUID());

  @Test
  void deliveredInOrderUntilClosed() {
    List<SeasonsEvent> got = new ArrayList<>();
    ApiEvents events = new ApiEvents(Runnable::run);
    SeasonsApi.Subscription sub = events.subscribe(got::add);
    SeasonsEvent one = new SeasonsEvent.PlayerJoined(NOW, new PlayerName(alex.uuid(), "Alex"));
    events.publish(one);
    events.subscribe(e -> {
      throw new IllegalStateException("a broken listener");
    });
    events.publish(one);
    sub.close();
    events.publish(one);
    assertThat(got).containsExactly(one, one);
  }

  @Test
  void nothingWhileNoSeasonIsRecorded() {
    assertThat(ApiService.changes(Optional.empty(), Optional.empty(), NOW)).isEmpty();
  }

  /**
   * A season recorded while Seasons runs is a phase change, even when it has moved on before the
   * next look: /season init, end --now and launch can all land within one second. (At startup the
   * first look is the state already there, so nothing is announced then.)
   */
  @Test
  void aSeasonRecordedWhileRunningIsAPhaseChange() {
    assertThat(ApiService.changes(Optional.empty(), Optional.of(SeasonState.init(2)), NOW))
        .containsExactly(new SeasonsEvent.PhaseChanged(NOW, 2, "active", Optional.of("open")));
    SeasonState launched =
        SeasonState.start(3, Stage.FRONTIER, Optional.of(NOW))
            .withFirst(new SeasonFirst(3, "nether", "reached the Nether", alex, "Alex", NOW));
    assertThat(ApiService.changes(Optional.empty(), Optional.of(launched), NOW))
        .containsExactlyInAnyOrder(
            new SeasonsEvent.PhaseChanged(NOW, 3, "active", Optional.of("frontier")),
            new SeasonsEvent.SeasonFirst(
                NOW, 3, "nether", "reached the Nether", new PlayerName(alex.uuid(), "Alex")));
  }

  @Test
  void aNewFirstAndAPhaseChange() {
    SeasonState before = SeasonState.start(3, Stage.FRONTIER, Optional.of(NOW));
    SeasonState after =
        before
            .withFirst(new SeasonFirst(3, "nether", "reached the Nether", alex, "Alex", NOW))
            .withPhase(new Phase.Countdown(Stage.FRONTIER, NOW, NOW.plusSeconds(60)));
    List<SeasonsEvent> events = ApiService.changes(Optional.of(before), Optional.of(after), NOW);
    assertThat(events)
        .containsExactlyInAnyOrder(
            new SeasonsEvent.PhaseChanged(NOW, 3, "countdown", Optional.of("frontier")),
            new SeasonsEvent.SeasonFirst(
                NOW, 3, "nether", "reached the Nether", new PlayerName(alex.uuid(), "Alex")));
    assertThat(ApiService.changes(Optional.of(after), Optional.of(after), NOW)).isEmpty();
  }

  @Test
  void homecomingAndTheDragon() {
    SeasonState before = SeasonState.start(3, Stage.FRONTIER, Optional.of(NOW));
    SeasonState after =
        before
            .withHomecoming(NOW)
            .withDragon(
                new DragonState(
                    Optional.empty(),
                    Optional.of(
                        new DragonState.Kill(
                            Optional.of(alex),
                            "Alex",
                            NOW,
                            DragonState.Egg.WAITING,
                            Optional.empty(),
                            Optional.empty())),
                    false));
    List<SeasonsEvent> events = ApiService.changes(Optional.of(before), Optional.of(after), NOW);
    assertThat(events).contains(new SeasonsEvent.HomecomingStarted(NOW, 3));
    assertThat(events)
        .contains(
            new SeasonsEvent.DragonSlain(NOW, 3, Optional.of(new PlayerName(alex.uuid(), "Alex"))));
  }
}
