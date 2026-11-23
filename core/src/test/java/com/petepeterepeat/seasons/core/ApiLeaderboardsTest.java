// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.api.LeaderboardEntry;
import com.petepeterepeat.seasons.api.Leaderboards;
import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.map.MapPublisher;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Seasons API's leaderboards for the season in progress. */
class ApiLeaderboardsTest {
  @TempDir Path dir;
  private final FakePlatform fake = new FakePlatform();
  private Seasons seasons;

  @AfterEach
  void stop() {
    if (seasons != null) {
      seasons.stop();
    }
  }

  @Test
  void aSeasonRecordedAMomentAgoCountsAtOnce() throws Exception {
    seasons =
        new Seasons(
            fake.platform(new Capabilities(EnumSet.allOf(Capability.class)), dir),
            "1.2.3",
            fake.clock,
            new Webhooks() {
              @Override
              public void post(URI webhook, String content) {}

              @Override
              public void close() {}
            },
            MapPublisher.NONE,
            Map.of(),
            new SerialWriter(Runnable::run),
            Optional.empty());
    seasons.start();
    PlayerRef sam = fake.join("Sam");
    fake.sessions.joined(sam, false);
    TestCommands.run(fake.commands.getFirst(), TestSource.console(), "/season init 2");
    fake.player(sam).statistics.put("minecraft:deaths", 1L);
    // No tick since the init: the snapshot of the season is a second behind.
    Leaderboards boards = seasons.leaderboards(2).get(10, TimeUnit.SECONDS);
    assertThat(boards.deaths())
        .extracting(e -> e.player().name(), LeaderboardEntry::value)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("Sam", 1L));
  }
}
