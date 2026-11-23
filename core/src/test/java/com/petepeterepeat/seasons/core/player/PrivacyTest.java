// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.player;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.port.PlayerId;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PrivacyTest {
  private final PlayerRecord alex = PlayerRecord.create(new PlayerId(UUID.randomUUID()), "Alex");

  @Test
  void aNewRecordIsShownOnLeaderboards() {
    assertThat(alex.leaderboardHidden()).isFalse();
  }

  @Test
  void hidingSurvivesEveryOtherChangeAndASave() throws Exception {
    PlayerRecord hidden =
        alex.withLeaderboardHidden(true)
            .withPlay(3, Instant.EPOCH, Duration.ofMinutes(5))
            .withName("Alexis")
            .withArchived(2);
    assertThat(hidden.leaderboardHidden()).isTrue();
    String json = PlayerStore.encode(hidden);
    assertThat(json).contains("leaderboardHidden");
    assertThat(PlayerStore.decode(json).leaderboardHidden()).isTrue();
    assertThat(
            PlayerStore.decode(PlayerStore.encode(hidden.withLeaderboardHidden(false)))
                .leaderboardHidden())
        .isFalse();
  }

  @Test
  void aRecordWrittenBeforePrivacyFlagsReadsAsShown() throws Exception {
    String json = PlayerStore.encode(alex);
    assertThat(json).doesNotContain("privacy");
    assertThat(PlayerStore.decode(json).leaderboardHidden()).isFalse();
  }
}
