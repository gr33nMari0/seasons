// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.player;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.player.LoginPlan.Plan;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.season.Phase;
import com.petepeterepeat.seasons.core.season.SeasonState;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoginPlanTest {
  private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
  private static final PlayerId ALEX = new PlayerId(UUID.randomUUID());

  private static final SeasonState INIT_ACTIVE = SeasonState.init(2);
  private static final SeasonState INIT_ENDED = INIT_ACTIVE.withPhase(new Phase.Intermission());
  private static final SeasonState LAUNCHED = INIT_ENDED.next(NOW);

  private static PlayerRecord played(int... seasons) {
    PlayerRecord record = PlayerRecord.create(ALEX, "Alex");
    for (int season : seasons) {
      record = record.withPlay(season, NOW, Duration.ofMinutes(5));
    }
    return record;
  }

  @Test
  void aPlayerWhoPlayedAFinishedSeasonIsArchivedIntoIt() {
    assertThat(LoginPlan.decide(Optional.of(played(2)), true, INIT_ENDED))
        .isEqualTo(new Plan.Archive(2, false));
    assertThat(LoginPlan.decide(Optional.of(played(2)), true, LAUNCHED))
        .isEqualTo(new Plan.Archive(2, true));
  }

  @Test
  void theSeasonInPlayIsNotArchived() {
    assertThat(LoginPlan.decide(Optional.of(played(2)), true, INIT_ACTIVE))
        .isEqualTo(new Plan.Play());
  }

  @Test
  void aPlayerWhoMissedSeasonsIsArchivedIntoTheLastOneTheyPlayed() {
    SeasonState four = LAUNCHED.withPhase(new Phase.Intermission()).next(NOW);
    assertThat(LoginPlan.decide(Optional.of(played(2)), true, four))
        .isEqualTo(new Plan.Archive(2, true));
    assertThat(LoginPlan.decide(Optional.of(played(2, 3).withArchived(2)), true, four))
        .isEqualTo(new Plan.Archive(3, true));
  }

  @Test
  void anArchivedPlayerEntersALaunchedSeasonOnce() {
    PlayerRecord archived = played(2).withArchived(2);
    assertThat(LoginPlan.decide(Optional.of(archived), true, LAUNCHED))
        .isEqualTo(new Plan.EnterSeason(3));
    assertThat(
            LoginPlan.decide(
                Optional.of(archived.withEntered(3, java.util.Map.of())), true, LAUNCHED))
        .isEqualTo(new Plan.Play());
  }

  @Test
  void aPlayerFromBeforeSeasonsBelongsToTheInitSeason() {
    assertThat(LoginPlan.decide(Optional.empty(), true, INIT_ACTIVE)).isEqualTo(new Plan.Play());
    assertThat(LoginPlan.decide(Optional.empty(), true, INIT_ENDED))
        .isEqualTo(new Plan.Archive(2, false));
    assertThat(LoginPlan.decide(Optional.empty(), true, LAUNCHED))
        .isEqualTo(new Plan.Archive(2, true));
  }

  @Test
  void someoneNewIsANewPlayer() {
    assertThat(LoginPlan.decide(Optional.empty(), false, LAUNCHED))
        .isEqualTo(new Plan.NewPlayer(3));
  }
}
