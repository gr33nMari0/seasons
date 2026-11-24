// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.port.Position;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@code /hof link go} is taken at the link, near one of its triggers (questions 334). */
class LinkReachTest {
  private static final String OVERWORLD = "minecraft:overworld";
  private static final String NETHER = "minecraft:the_nether";

  private static HallLayout.Link link(HallLayout.Trigger... triggers) {
    return HallLayout.Link.of(
        "l1",
        Optional.of(new HallLayout.Target.Hall()),
        List.of(triggers),
        HallLayout.Access.EVERYONE);
  }

  private static Position at(String dimension, double x, double y, double z) {
    return Position.of(dimension, x, y, z);
  }

  @Test
  void aBlockTriggerIsReachedWithinSixBlocks() {
    HallLayout.Link button =
        link(new HallLayout.Trigger.OnBlock(at(OVERWORLD, 10, 64, 10), HallLayout.BlockKind.USE));
    assertThat(LinkService.reaches(button, at(OVERWORLD, 12.5, 64, 10.5))).isTrue();
    assertThat(LinkService.reaches(button, at(OVERWORLD, 16.5, 64, 10.5))).isTrue();
    assertThat(LinkService.reaches(button, at(OVERWORLD, 17.5, 64, 10.5))).isFalse();
    assertThat(LinkService.reaches(button, at(NETHER, 10.5, 64, 10.5))).isFalse();
  }

  @Test
  void anAreaIsReachedNearItsBox() {
    HallLayout.Link door =
        link(new HallLayout.Trigger.Area(Bounds.between(OVERWORLD, 0, 64, 0, 0, 66, 2)));
    assertThat(LinkService.reaches(door, at(OVERWORLD, 0.5, 64, 1.5))).isTrue();
    assertThat(LinkService.reaches(door, at(OVERWORLD, 6.5, 64, 1.5))).isTrue();
    assertThat(LinkService.reaches(door, at(OVERWORLD, 8, 64, 1.5))).isFalse();
  }

  @Test
  void aFloatingTextIsReachedNearIt() {
    HallLayout.Link text =
        link(
            new HallLayout.Trigger.Text(
                at(OVERWORLD, 0, 65, 0), List.of("Hall"), 1, "center", 0, false));
    assertThat(LinkService.reaches(text, at(OVERWORLD, 3, 64, 0))).isTrue();
    assertThat(LinkService.reaches(text, at(OVERWORLD, 9, 64, 0))).isFalse();
  }

  @Test
  void aLinkWithNoTriggerIsReachedByNobody() {
    assertThat(LinkService.reaches(link(), at(OVERWORLD, 0, 64, 0))).isFalse();
  }
}
