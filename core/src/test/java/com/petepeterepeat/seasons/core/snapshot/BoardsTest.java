// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.api.LeaderboardEntry;
import com.petepeterepeat.seasons.api.Leaderboards;
import com.petepeterepeat.seasons.api.PlayerName;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BoardsTest {
  private final PlayerName alex = new PlayerName(UUID.randomUUID(), "Alex");
  private final PlayerName sam = new PlayerName(UUID.randomUUID(), "Sam");

  @Test
  void bestFirstInSecondsAndBlocks() {
    Map<PlayerName, Map<String, Long>> stats = new LinkedHashMap<>();
    stats.put(
        alex,
        Map.of(
            "minecraft:deaths",
            1L,
            "minecraft:play_time",
            72_000L,
            "minecraft:walk_one_cm",
            150_000L));
    stats.put(
        sam,
        Map.of(
            "minecraft:deaths",
            4L,
            "minecraft:play_time",
            2_000L,
            "minecraft:boat_one_cm",
            20_000L,
            "minecraft:fly_one_cm",
            10_000L));
    Leaderboards boards = Boards.of(3, stats, Set.of(sam.id()));
    assertThat(boards.deaths()).extracting(e -> e.player().name()).containsExactly("Sam", "Alex");
    assertThat(boards.playTime().getFirst()).isEqualTo(new LeaderboardEntry(alex, 3600, false));
    assertThat(boards.distance()).extracting(LeaderboardEntry::value).containsExactly(1500L, 300L);
    assertThat(boards.deaths().getFirst().hidden()).isTrue();
  }

  @Test
  void tiesByNameAndMissingStatisticsAsZero() {
    Map<PlayerName, Map<String, Long>> stats = new LinkedHashMap<>();
    stats.put(sam, Map.of());
    stats.put(alex, Map.of());
    assertThat(Boards.of(3, stats, Set.of()).deaths())
        .extracting(e -> e.player().name())
        .containsExactly("Alex", "Sam");
  }
}
