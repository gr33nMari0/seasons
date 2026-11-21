// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.petepeterepeat.seasons.core.port.TemplateScan;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Where a stamp's copies go (questions 232 and 233). */
class StampPlanTest {
  @Test
  void rotationMatchesTheGame() {
    // The game's StructureTemplate.transform with a pivot at the origin: east (+x) turns to south
    // (+z) at 90° clockwise seen from above, to west at 180° and to north at 270°.
    assertThat(StampPlan.turn(1, 0, 0)).containsExactly(1, 0);
    assertThat(StampPlan.turn(1, 0, 90)).containsExactly(0, 1);
    assertThat(StampPlan.turn(1, 0, 180)).containsExactly(-1, 0);
    assertThat(StampPlan.turn(1, 0, 270)).containsExactly(0, -1);
    assertThat(StampPlan.turn(2, 3, 90)).containsExactly(-3, 2);
    assertThat(StampPlan.turn(2, 3, 270)).containsExactly(3, -2);
  }

  @Test
  void aTurnedFigureFacesTheTurnedWay() {
    assertThat(List.of(StampPlan.yaw(0), StampPlan.yaw(90), StampPlan.yaw(180), StampPlan.yaw(270)))
        .containsExactly(0f, 90f, 180f, -90f);
  }

  @Test
  void theDefaultStepIsTheTurnedWidth() {
    TemplateScan scan = new TemplateScan(5, 4, 3, List.of(), List.of());
    assertThat(StampPlan.defaultStep(scan, 0)).containsExactly(5, 0, 0);
    assertThat(StampPlan.defaultStep(scan, 90)).containsExactly(3, 0, 0);
    assertThat(StampPlan.defaultStep(scan, 180)).containsExactly(5, 0, 0);
    assertThat(StampPlan.defaultStep(scan, 270)).containsExactly(3, 0, 0);
  }

  @Test
  void aPlanReadsBackAsWritten() {
    StampPlan plan =
        new StampPlan(
            "season-2",
            "alcove",
            Template.Source.STRUCTURE,
            "minecraft:overworld",
            22,
            -60,
            -5,
            270,
            0,
            0,
            8,
            List.of("c4", "c5"),
            "Admin");
    assertThat(StampPlan.read(plan.write())).isEqualTo(plan);
    assertThatThrownBy(() -> StampPlan.read("gallery=x\n"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void copiesHaveTheirFootprintAnchorsAndMarkers() {
    TemplateScan scan =
        new TemplateScan(
            3,
            2,
            2,
            List.of(
                new TemplateScan.Block(
                    1, 0, 0, "minecraft:barrel", 0, java.util.Optional.empty(), false),
                new TemplateScan.Block(
                    1,
                    1,
                    0,
                    "minecraft:structure_block",
                    0,
                    java.util.Optional.of("seasons:archive"),
                    true),
                new TemplateScan.Block(
                    0,
                    0,
                    1,
                    "minecraft:structure_block",
                    0,
                    java.util.Optional.of("seasons:figure"),
                    true)),
            List.of());
    Template template = new Template("t", Template.Source.BUNDLED, new byte[0], scan);
    StampPlan plan =
        new StampPlan(
            "g",
            "t",
            Template.Source.BUNDLED,
            "minecraft:overworld",
            10,
            64,
            10,
            90,
            2,
            0,
            0,
            List.of("c1", "c2"),
            "Admin");
    List<StampPlan.Copy> copies = plan.copies(template);
    assertThat(copies).hasSize(2);
    StampPlan.Copy first = copies.getFirst();
    assertThat(first.bounds().describe()).isEqualTo("9, 64, 10 to 10, 65, 12");
    assertThat(first.anchors())
        .extracting(a ->
            a.type().id()
                + " "
                + a.at().blockX()
                + ","
                + a.at().blockY()
                + ","
                + a.at().blockZ()
                + " "
                + a.at().yaw())
        .containsExactly("archive 10,64,11 90.0", "figure 9,64,10 90.0");
    assertThat(first.markers())
        .extracting(p -> p.blockX() + "," + p.blockY() + "," + p.blockZ())
        .containsExactly("10,65,11", "9,64,10");
    assertThat(copies.get(1).origin().blockX()).isEqualTo(12);
  }
}
