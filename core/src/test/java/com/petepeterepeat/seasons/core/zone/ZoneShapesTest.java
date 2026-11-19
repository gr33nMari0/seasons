// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.zone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.petepeterepeat.seasons.core.port.Position;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** Which blocks a zone holds. */
class ZoneShapesTest {
  @Test
  void aCylinderHoldsTheBlocksWhoseMiddlesAreWithinItsRadius() {
    ZoneShape circle = new ZoneShape.Cylinder(0, 0, 10, OptionalInt.empty(), OptionalInt.empty());
    assertThat(circle.contains(0, 64, 0)).isTrue();
    assertThat(circle.contains(9, 64, 0)).isTrue();
    assertThat(circle.contains(-10, 64, 0)).isTrue();
    assertThat(circle.contains(10, 64, 0)).isFalse();
    assertThat(circle.contains(7, 64, 7)).isFalse();
    assertThat(circle.contains(0, -64, 0)).isTrue();
  }

  @Test
  void maxYIsInclusiveSoTheNetherRoofStaysOpen() {
    ZoneShape nether = new ZoneShape.Cylinder(0, 0, 100, OptionalInt.empty(), OptionalInt.of(127));
    assertThat(nether.contains(5, 127, 5)).isTrue();
    assertThat(nether.contains(5, 128, 5)).isFalse();
    ZoneShape above = new ZoneShape.Cylinder(0, 0, 100, OptionalInt.of(60), OptionalInt.empty());
    assertThat(above.contains(5, 59, 5)).isFalse();
    assertThat(above.contains(5, 60, 5)).isTrue();
  }

  @Test
  void aBoxHoldsBothCorners() {
    ZoneShape box = ZoneShape.Box.between(10, 0, 10, 0, 5, 0);
    assertThat(box.contains(0, 0, 0)).isTrue();
    assertThat(box.contains(10, 5, 10)).isTrue();
    assertThat(box.contains(11, 5, 10)).isFalse();
    assertThat(box.contains(5, 6, 5)).isFalse();
  }

  @Test
  void anOutsideCircleIsExactlyWhatTheCylinderLeavesOut() {
    ZoneShape cylinder =
        new ZoneShape.Cylinder(3000, -20, 25, OptionalInt.empty(), OptionalInt.empty());
    ZoneShape outside = new ZoneShape.OutsideCircle(3000, -20, 25);
    for (int x = 2960; x <= 3040; x++) {
      for (int z = -60; z <= 20; z++) {
        assertThat(outside.contains(x, 64, z)).isNotEqualTo(cylinder.contains(x, 64, z));
      }
    }
    assertThat(outside.contains(3100, -64, 0)).isTrue();
    assertThat(outside.contains(3100, 319, 0)).isTrue();
  }

  @Test
  void anOutsideBoxHoldsEveryColumnBeyondItAtEveryHeight() {
    ZoneShape edge = ZoneShape.OutsideBox.between(100, 100, -100, -100);
    assertThat(edge.contains(100, 64, -100)).isFalse();
    assertThat(edge.contains(0, -64, 0)).isFalse();
    assertThat(edge.contains(101, 64, 0)).isTrue();
    assertThat(edge.contains(0, 320, -101)).isTrue();
  }

  @Test
  void heightZonesIncludeTheirYAcrossTheWholeDimension() {
    ZoneShape sky = new ZoneShape.YAbove(128);
    assertThat(sky.contains(0, 128, 0)).isTrue();
    assertThat(sky.contains(25_000_000, 300, -25_000_000)).isTrue();
    assertThat(sky.contains(0, 127, 0)).isFalse();
    ZoneShape roof = new ZoneShape.YBelow(127);
    assertThat(roof.contains(5, 127, 5)).isTrue();
    assertThat(roof.contains(5, 128, 5)).isFalse();
    assertThat(roof.reaches(0, 0, 15, 15)).isTrue();
  }

  @Test
  void anOutsideShapeDoesntReachChunksWhollyInsideIt() {
    ZoneShape frontier = new ZoneShape.OutsideCircle(0, 0, 100);
    assertThat(frontier.reaches(0, 0, 15, 15)).isFalse();
    assertThat(frontier.reaches(-16, -16, -1, -1)).isFalse();
    assertThat(frontier.reaches(96, 0, 111, 15)).isTrue();
    ZoneShape edge = new ZoneShape.OutsideBox(-100, -100, 100, 100);
    assertThat(edge.reaches(96, 96, 100, 100)).isFalse();
    assertThat(edge.reaches(96, 96, 111, 111)).isTrue();
    ZoneShape cylinder =
        new ZoneShape.Cylinder(0, 0, 100, OptionalInt.empty(), OptionalInt.empty());
    assertThat(cylinder.reaches(0, 0, 15, 15)).isTrue();
    assertThat(cylinder.reaches(112, 0, 127, 15)).isFalse();
  }

  @Test
  void badShapesAreRefused() {
    assertThatThrownBy(
            () -> new ZoneShape.Cylinder(0, 0, 0, OptionalInt.empty(), OptionalInt.empty()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ZoneShape.Cylinder(0, 0, 5, OptionalInt.of(10), OptionalInt.of(9)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ZoneShape.OutsideCircle(0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ZoneShape.OutsideBox(5, 0, 4, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aZoneLeavesOutOtherDimensionsAndItsExemptions() {
    Zone zone =
        new Zone(
            "old-world",
            "minecraft:overworld",
            new ZoneShape.Cylinder(0, 0, 50, OptionalInt.empty(), OptionalInt.empty()),
            ZoneMode.BLOCK,
            new UnlockCondition.Homecoming(),
            ZoneMode.OFF,
            List.of(ZoneShape.Box.between(-5, -64, -5, 5, 320, 5)),
            Optional.empty());
    assertThat(zone.contains(Position.of("minecraft:overworld", 20.5, 64, 0.5))).isTrue();
    assertThat(zone.contains(Position.of("minecraft:overworld", 1.5, 64, 1.5))).isFalse();
    assertThat(zone.contains(Position.of("minecraft:the_nether", 20.5, 64, 0.5))).isFalse();
    assertThat(zone.displayName()).isEqualTo("old world");
  }

  @Test
  void zoneIdsAreSlugsAndNeverHomecoming() {
    assertThat(Zone.validId("peter-base")).isTrue();
    assertThat(Zone.validId("Peter_Base")).isFalse();
    assertThat(Zone.validId("-base")).isFalse();
    assertThat(Zone.validId("homecoming")).isFalse();
  }
}
