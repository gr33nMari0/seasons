// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.port.Position;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Positions typed in commands (questions 264, A62). */
class PositionArgumentsTest {
  private static final String OVERWORLD = "minecraft:overworld";
  private static final Optional<Position> STANDING =
      Optional.of(new Position(OVERWORLD, 100.5, -60, 100.5, -90f, 0f));

  private static Positions.Read read(String text, Optional<Position> origin) {
    return Positions.block(Words.of(text), 0, origin, OVERWORLD);
  }

  private static Position block(String text) {
    return ((Positions.Read.Block) read(text, STANDING)).block();
  }

  @Test
  void absoluteRelativeLocalAndHere() {
    assertThat(block("193 64 110")).isEqualTo(Position.of(OVERWORLD, 193, 64, 110));
    assertThat(block("~-20 ~ ~5")).isEqualTo(Position.of(OVERWORLD, 80, -60, 105));
    assertThat(block("~ ~1.5 ~-0.6")).isEqualTo(Position.of(OVERWORLD, 100, -59, 99));
    // Facing +x (yaw -90): ^ ^ ^10 is ten blocks east, ^1 is one to the left (north).
    assertThat(block("^ ^ ^10")).isEqualTo(Position.of(OVERWORLD, 110, -60, 100));
    assertThat(block("^1 ^ ^")).isEqualTo(Position.of(OVERWORLD, 100, -60, 99));
    assertThat(block("here")).isEqualTo(Position.of(OVERWORLD, 100, -60, 100));
    assertThat(((Positions.Read.Block) read("here 1 2 3", STANDING)).used()).isEqualTo(1);
  }

  @Test
  void mixedLocalWordsAndNonsenseAreRefused() {
    assertThat(read("^ 1 ^", STANDING)).isEqualTo(new Positions.Read.Failed(Positions.BAD));
    assertThat(read("1 2", STANDING)).isEqualTo(new Positions.Read.Failed(Positions.BAD));
    assertThat(read("1d 2 3", STANDING)).isEqualTo(new Positions.Read.Failed(Positions.BAD));
    assertThat(read("NaN 2 3", STANDING)).isEqualTo(new Positions.Read.Failed(Positions.BAD));
  }

  @Test
  void theConsoleMustGiveNumbers() {
    assertThat(read("~ ~ ~", Optional.empty()))
        .isEqualTo(new Positions.Read.Failed(Positions.CONSOLE));
    assertThat(read("here", Optional.empty()))
        .isEqualTo(new Positions.Read.Failed(Positions.CONSOLE));
    assertThat(read("300 -60 300", Optional.empty()))
        .isEqualTo(new Positions.Read.Block(Position.of(OVERWORLD, 300, -60, 300), 3));
  }

  @Test
  void aTypedPositionTakesAYawAfterIt() {
    Positions.Read read = Positions.typed("~1 ~ ~ 90", STANDING, OVERWORLD, 0f);
    assertThat(((Positions.Read.Block) read).block())
        .isEqualTo(new Position(OVERWORLD, 101, -60, 100, 90f, 0f));
    assertThat(Positions.typed("1 2 3 east", STANDING, OVERWORLD, 0f))
        .isEqualTo(new Positions.Read.Failed(Positions.BAD));
    assertThat(List.of(Positions.typed("1 2 3", STANDING, OVERWORLD, 45f)))
        .first()
        .isEqualTo(new Positions.Read.Block(new Position(OVERWORLD, 1, 2, 3, 45f, 0f), 3));
  }
}
