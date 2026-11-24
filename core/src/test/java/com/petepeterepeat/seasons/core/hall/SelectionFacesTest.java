// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** M8, Nudging boxes: a face moves, never past the other; a box shifts; link text stays safe. */
class SelectionFacesTest {
  private static final String OVERWORLD = "minecraft:overworld";
  private static final Bounds BOX = Bounds.between(OVERWORLD, 0, 60, 0, 4, 62, 4);

  @Test
  void aFaceMovesOutAndIn() {
    assertThat(BoxEdits.nudge(BOX, "+x", 3))
        .contains(Bounds.between(OVERWORLD, 0, 60, 0, 7, 62, 4));
    assertThat(BoxEdits.nudge(BOX, "-y", 2))
        .contains(Bounds.between(OVERWORLD, 0, 58, 0, 4, 62, 4));
    assertThat(BoxEdits.nudge(BOX, "+z", -4))
        .contains(Bounds.between(OVERWORLD, 0, 60, 0, 4, 62, 0));
  }

  @Test
  void aFaceCantCrossItsOpposite() {
    assertThat(BoxEdits.nudge(BOX, "+z", -5)).isEqualTo(Optional.empty());
    assertThat(BoxEdits.nudge(BOX, "-x", -5)).isEqualTo(Optional.empty());
  }

  @Test
  void aBoxShiftsWhole() {
    assertThat(BoxEdits.shift(BOX, "-z", 4))
        .isEqualTo(Bounds.between(OVERWORLD, 0, 60, -4, 4, 62, 0));
  }

  @Test
  void onlyStyleTagsSurvive() {
    List<String> allowed =
        List.of("color", "gradient", "rainbow", "decorations", "reset", "shadow_color");
    assertThat(
            LinkText.clean(
                "<gold><bold>Hall</bold> <click:run_command:'/op x'>now</click>", allowed))
        .isEqualTo("<gold><bold>Hall</bold> now");
    assertThat(LinkText.clean("<hover:show_text:'x'><#ff0000>red<insert:y>", allowed))
        .isEqualTo("<#ff0000>red");
    assertThat(LinkText.plain("<gold>Season <bold>3")).isEqualTo("Season 3");
  }
}
