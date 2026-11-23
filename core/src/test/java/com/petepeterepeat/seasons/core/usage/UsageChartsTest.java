// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.config.ConfigLoader;
import com.petepeterepeat.seasons.core.config.SeasonsConfig;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class UsageChartsTest {
  private final SeasonsConfig defaults = ConfigLoader.defaults().config();

  @Test
  void theChartsAreWhetherSeasonsWebIsInstalledTheCellModesAndTheSeasonsRun() {
    Map<String, String> values = UsageCharts.values(defaults, OptionalInt.of(3), true);

    assertThat(values)
        .containsExactly(
            Map.entry("seasons_web_installed", "yes"),
            Map.entry("cell_assignment", "random"),
            Map.entry("hall_access", "open"),
            Map.entry("seasons_run", "3"));
  }

  @Test
  void modesAreWrittenAsConfigFilesWriteThem() {
    SeasonsConfig config =
        withCells(defaults, SeasonsConfig.CellAssignment.MANUAL, SeasonsConfig.CellAccess.GATE);

    Map<String, String> values = UsageCharts.values(config, OptionalInt.of(1), false);

    assertThat(values)
        .containsEntry("seasons_web_installed", "no")
        .containsEntry("cell_assignment", "manual")
        .containsEntry("hall_access", "gate");
  }

  @Test
  void seasonsRunAreBucketedFromTenAndNoneBeforeTheFirstIsRecorded() {
    assertThat(UsageCharts.seasonsRun(OptionalInt.empty())).isEqualTo("none");
    assertThat(UsageCharts.seasonsRun(OptionalInt.of(1))).isEqualTo("1");
    assertThat(UsageCharts.seasonsRun(OptionalInt.of(9))).isEqualTo("9");
    assertThat(UsageCharts.seasonsRun(OptionalInt.of(10))).isEqualTo("10+");
    assertThat(UsageCharts.seasonsRun(OptionalInt.of(57))).isEqualTo("10+");
  }

  private static SeasonsConfig withCells(
      SeasonsConfig config,
      SeasonsConfig.CellAssignment assignment,
      SeasonsConfig.CellAccess access) {
    SeasonsConfig.Cells cells = config.cells();
    SeasonsConfig.Cells changed =
        new SeasonsConfig.Cells(
            assignment,
            cells.minPlaytime(),
            access,
            cells.containers(),
            cells.gateBlock(),
            cells.gates(),
            cells.moveOut());
    return new SeasonsConfig(
        config.schedule(),
        config.launch(),
        config.archive(),
        config.resets(),
        changed,
        config.decorating(),
        config.pets(),
        config.firsts(),
        config.hof(),
        config.zones(),
        config.dragon(),
        config.deaths(),
        config.curate(),
        config.wand(),
        config.admin(),
        config.logging(),
        config.api(),
        config.awards());
  }
}
