// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.map.MapPublisher;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StartupSummaryTest {
  @Test
  void aFreshInstallSaysSeasonsIsIdleAndWhatToSetUp() {
    var facts =
        new StartupSummary.Facts(
            "1.1.0",
            "Paper",
            25,
            Optional.empty(),
            new StartupSummary.Hall(false, 0, 0, 0),
            Optional.empty(),
            0,
            0,
            0,
            false,
            false,
            new MapPublisher.Status.NotInstalled(),
            0,
            2,
            412);

    assertThat(StartupSummary.lines(facts))
        .containsExactly(
            "Seasons 1.1.0 on Paper, Java 25",
            "Season: none recorded. Seasons is idle until you run /season init <season>.",
            "Hall of Fame: not set up yet; /hof help shows where to start",
            "Journal clean; 0 player records, 0 waiting to be archived",
            "Discord: public webhook not set, admin webhook not set",
            "BlueMap: not installed, so no map markers",
            "Ready in 412 ms. Setup check found 2 problems: run /hof hall check");
  }

  @Test
  void aRunningServerShowsItsSeasonHallAndHooks() {
    var facts =
        new StartupSummary.Facts(
            "1.1.0",
            "Paper",
            25,
            Optional.of(
                new StartupSummary.Season(3, "Active, Frontier stage", Optional.of("1 Dec"), 1)),
            new StartupSummary.Hall(true, 1, 24, 1),
            Optional.empty(),
            1,
            1,
            2,
            true,
            false,
            new MapPublisher.Status.Published(12, 3),
            1,
            0,
            90);

    assertThat(StartupSummary.lines(facts))
        .containsExactly(
            "Seasons 1.1.0 on Paper, Java 25",
            "Season 3: Active, Frontier stage, ends 1 Dec, 1 zone in force",
            "Hall of Fame: hub set, 1 gallery, 24 cells (1 free)",
            "Journal: finishing 1 interrupted operation from before the restart; 1 player record,"
                + " 2 waiting to be archived",
            "Discord: public webhook set, admin webhook not set",
            "BlueMap: 12 markers on 3 maps",
            "Ready in 90 ms. Setup check found 1 problem: run /season validate");
  }

  @Test
  void aJournalThatWontOpenIsSaid() {
    var facts =
        new StartupSummary.Facts(
            "1.1.0",
            "Paper",
            25,
            Optional.empty(),
            new StartupSummary.Hall(true, 0, 0, 0),
            Optional.of("disk full"),
            0,
            0,
            0,
            false,
            false,
            new MapPublisher.Status.Waiting(),
            0,
            0,
            5);

    assertThat(StartupSummary.lines(facts))
        .contains(
            "Hall of Fame: hub set, 0 galleries, 0 cells (0 free)",
            "Journal unavailable (disk full): nobody can be archived until it is fixed; 0 player"
                + " records, 0 waiting to be archived",
            "BlueMap: found; markers follow once it has loaded",
            "Ready in 5 ms. /season info for details.");
  }
}
