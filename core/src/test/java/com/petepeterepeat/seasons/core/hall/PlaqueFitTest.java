// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Where plaque text stands so it doesn't clip into blocks (1.4.1 plan, H4; questions 344 to 346).
 */
class PlaqueFitTest {
  private static final String OVERWORLD = "minecraft:overworld";

  /** A plaque as messages.yml's hof.plaque makes it: three lines, the last wrapping. */
  private static final Message PLAQUE =
      new Message(
          "<name> · Season <season><newline>Advancements: <completed> / <visible><newline><gray>Right-click"
              + " below for the season card</gray>",
          Map.of("name", "Alex", "season", "2", "completed", "10", "visible", "120"));

  private final Set<List<Integer>> solid = new HashSet<>();

  private boolean isSolid(Position at) {
    return solid.contains(List.of(at.blockX(), at.blockY(), at.blockZ()));
  }

  private static final Bounds CELL = new Bounds(OVERWORLD, 0, 64, 0, 8, 70, 8);

  private void wall(int x0, int x1, int y0, int y1, int z) {
    for (int x = x0; x <= x1; x++) {
      for (int y = y0; y <= y1; y++) {
        solid.add(List.of(x, y, z));
      }
    }
  }

  private static Position block(int x, int y, int z) {
    return Position.of(OVERWORLD, x, y, z);
  }

  @Test
  void theTextIsAboutFiveBlocksWideAndFourLinesHigh() {
    double[] size = PlaqueFit.size(PLAQUE);
    assertThat(size[0]).isBetween(5.0, 5.1);
    assertThat(size[1]).isBetween(1.0, 1.1);
  }

  @Test
  void onAWallItStandsInFrontOfTheFaceItFacesFixed() {
    wall(0, 8, 64, 68, 8);

    PlaqueFit.Placed placed =
        PlaqueFit.place(
            block(4, 66, 8),
            Optional.of(PlaqueFit.Facing.NORTH),
            Optional.of(CELL),
            PLAQUE,
            1.0,
            this::isSolid);

    assertThat(placed.fixed()).isTrue();
    assertThat(placed.at().x()).isEqualTo(4.5);
    assertThat(placed.at().z()).isCloseTo(7.98, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(placed.at().yaw()).isEqualTo(180f);
    assertThat(placed.fit()).isEqualTo(new PlaqueFit.Fit(1.0, 1.0, true));
    assertThat(placed.guessed()).isEmpty();
  }

  @Test
  void inANookItShrinksToFitBetweenThePillars() {
    wall(0, 8, 64, 68, 8);
    wall(2, 2, 64, 68, 7);
    wall(6, 6, 64, 68, 7);

    PlaqueFit.Placed placed =
        PlaqueFit.place(
            block(4, 66, 8),
            Optional.of(PlaqueFit.Facing.NORTH),
            Optional.of(CELL),
            PLAQUE,
            1.0,
            this::isSolid);

    assertThat(placed.fit().fits()).isTrue();
    assertThat(placed.fit().shown()).isLessThan(1.0).isGreaterThanOrEqualTo(0.5);
    assertThat(PlaqueFit.size(PLAQUE)[0] * placed.fit().shown()).isLessThanOrEqualTo(3.0);
  }

  @Test
  void withNoRoomAtAllItSaysSoAtTheSmallestSize() {
    wall(0, 8, 64, 68, 8);
    wall(4, 4, 66, 66, 7);

    PlaqueFit.Placed placed =
        PlaqueFit.place(
            block(4, 66, 8),
            Optional.of(PlaqueFit.Facing.NORTH),
            Optional.of(CELL),
            PLAQUE,
            1.0,
            this::isSolid);

    assertThat(placed.fit()).isEqualTo(new PlaqueFit.Fit(1.0, PlaqueFit.MIN, false));
  }

  @Test
  void withoutAFacingItGuessesTheOpenSideFacingIntoTheCell() {
    wall(0, 8, 64, 68, 8);

    PlaqueFit.Placed placed =
        PlaqueFit.place(
            block(4, 66, 8), Optional.empty(), Optional.of(CELL), PLAQUE, 1.0, this::isSolid);

    assertThat(placed.guessed()).contains(PlaqueFit.Facing.NORTH);
    assertThat(placed.fixed()).isTrue();
  }

  @Test
  void aWallOnTheEastSideFacesWest() {
    for (int y = 64; y <= 68; y++) {
      for (int z = 0; z <= 8; z++) {
        solid.add(List.of(8, y, z));
      }
    }

    PlaqueFit.Placed placed =
        PlaqueFit.place(
            block(8, 66, 4), Optional.empty(), Optional.of(CELL), PLAQUE, 1.0, this::isSolid);

    assertThat(placed.guessed()).contains(PlaqueFit.Facing.WEST);
    assertThat(placed.at().x()).isCloseTo(7.98, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(placed.at().yaw()).isEqualTo(90f);
  }

  @Test
  void onTheFloorWithRoomAllRoundItFloatsAndTurnsAsBefore() {
    PlaqueFit.Placed placed =
        PlaqueFit.place(
            block(4, 64, 4), Optional.empty(), Optional.of(CELL), PLAQUE, 2.0, this::isSolid);

    assertThat(placed.fixed()).isFalse();
    assertThat(placed.at().y()).isEqualTo(65.2);
    assertThat(placed.fit()).isEqualTo(new PlaqueFit.Fit(2.0, 2.0, true));
    assertThat(placed.guessed()).isEmpty();
  }

  @Test
  void aLecternByAWallStopsTurningAndFacesIntoTheCell() {
    // The lectern, solid, with air above; the wall right behind it.
    solid.add(List.of(4, 64, 7));
    wall(0, 8, 64, 68, 8);

    PlaqueFit.Placed placed =
        PlaqueFit.place(
            block(4, 64, 7), Optional.empty(), Optional.of(CELL), PLAQUE, 1.0, this::isSolid);

    assertThat(placed.fixed()).isTrue();
    assertThat(placed.guessed()).contains(PlaqueFit.Facing.NORTH);
    assertThat(placed.fit().fits()).isTrue();
  }
}
