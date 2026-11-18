// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.season;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SeasonStoreTest {
  private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");

  @TempDir Path dir;

  private SeasonStore store() {
    return new SeasonStore(dir.resolve("data/season.json"), new SerialWriter(Runnable::run));
  }

  @Test
  void noFileMeansNoSeason() throws IOException {
    assertThat(store().load()).isEmpty();
  }

  @Test
  void everyPhaseRoundTrips() throws IOException {
    SeasonState base =
        SeasonState.start(2, Stage.FRONTIER, Optional.of(NOW))
            .withPlannedEnd(
                Optional.of(ZonedDateTime.parse("2026-12-05T20:00-05:00[America/New_York]")), NOW)
            .withRemindersSent(Set.of(Duration.ofDays(7), Duration.ofHours(1)))
            .withAutoEndStarted()
            .withHomecoming(NOW)
            .withCountdownInterrupted(Optional.of(NOW));
    for (Phase phase :
        new Phase[] {
          new Phase.Active(Stage.OPEN),
          new Phase.Countdown(Stage.FRONTIER, NOW, NOW.plus(Duration.ofMinutes(10))),
          new Phase.Archiving(),
          new Phase.Intermission()
        }) {
      SeasonState state = base.withPhase(phase);
      store().save(state).join();
      assertThat(store().load()).contains(state);
    }
  }

  @Test
  void theDecoratingWindowRoundTrips() throws IOException {
    PlayerId owner = new PlayerId(UUID.randomUUID());
    SeasonState state =
        SeasonState.start(2, Stage.OPEN, Optional.empty())
            .withPhase(new Phase.Intermission())
            .withDecorating(
                Decorating.NONE.opened(NOW).lockedAtEnd().withLockAt(owner, NOW.plusSeconds(900)));
    store().save(state).join();

    assertThat(store().load()).contains(state);
    assertThat(state.decorating().afterArchive(owner, NOW)).isTrue();
    assertThat(state.decorating().afterArchive(owner, NOW.plusSeconds(900))).isFalse();
    assertThat(state.next(NOW).decorating()).isEqualTo(Decorating.NONE);
  }

  @Test
  void seasonFirstsRoundTripAndOutliveTheirSeason() throws IOException {
    PlayerId alex = new PlayerId(UUID.randomUUID());
    SeasonFirst first =
        new SeasonFirst(
            2, "advancement:minecraft:story/mine_stone", "Stone Age", alex, "Alex", NOW);
    SeasonState state = SeasonState.start(2, Stage.OPEN, Optional.empty()).withFirst(first);
    store().save(state).join();

    assertThat(store().load()).contains(state);
    SeasonState next = state.next(NOW);
    assertThat(next.firsts(2)).containsExactly(first);
    assertThat(next.first("advancement:minecraft:story/mine_stone")).isEmpty();
  }

  @Test
  void writesAFormatVersion() throws IOException {
    store().save(SeasonState.start(2, Stage.OPEN, Optional.empty())).join();

    assertThat(Files.readString(dir.resolve("data/season.json"))).contains("\"format-version\": 1");
  }

  @Test
  void refusesANewerFormat() throws IOException {
    Files.createDirectories(dir.resolve("data"));
    Files.writeString(dir.resolve("data/season.json"), "{\"format-version\": 9, \"season\": 2}");

    assertThatThrownBy(() -> store().load()).hasMessageContaining("format-version 9");
  }
}
