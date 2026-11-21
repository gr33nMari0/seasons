// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** One value changed in a YAML file people edit by hand, and nothing else. */
class YamlEditsTest {
  private static String set(String text, String path, String value) throws Exception {
    return YamlEdits.set(text, List.of(path.split("\\.")), value);
  }

  @Test
  void aValueChangesInPlaceAndEverythingElseStays() throws Exception {
    String file =
        """
        # Seasons settings.
        schema-version: 1

        hof:
          # The cooldown between visits.
          cooldown: 0s       # none
          entry: { bedDistance: 3, anyBed: true }
          reminders: [7d, 1d]
        """;
    assertThat(set(file, "hof.cooldown", "30s"))
        .isEqualTo(file.replace("cooldown: 0s", "cooldown: 30s"));
    assertThat(set(file, "hof.entry.bedDistance", "5"))
        .isEqualTo(file.replace("bedDistance: 3", "bedDistance: 5"));
    assertThat(set(file, "hof.reminders", "[2d, 1h]"))
        .isEqualTo(file.replace("[7d, 1d]", "[2d, 1h]"));
  }

  @Test
  void aBlockListOrAnEmptyValueGoesOnItsKeysLine() throws Exception {
    String file = "schedule:\n  reminders:\n  - 7d\n  - 1d\n  autoEnd:\n  endCountdown: 2m\n";
    assertThat(set(file, "schedule.reminders", "[2d]"))
        .isEqualTo("schedule:\n  reminders: [2d]\n  autoEnd:\n  endCountdown: 2m\n");
    assertThat(set(file, "schedule.autoEnd", "true"))
        .isEqualTo(
            "schedule:\n  reminders:\n  - 7d\n  - 1d\n  autoEnd: true\n  endCountdown: 2m\n");
  }

  @Test
  void anAwardsSettingIsSetInsideItsFlowMappingOrAddedAsANewAward() throws Exception {
    String file =
        "schema-version: 1\nawards:\n  most-jumps: { measure: minecraft:jump, item: rabbit_foot }\n";
    assertThat(set(file, "awards.most-jumps.item", "stick"))
        .isEqualTo(
            "schema-version: 1\nawards:\n  most-jumps: { measure: minecraft:jump, item: stick }\n");
    assertThat(set(file, "awards.most-deaths.item", "wither_skeleton_skull"))
        .isEqualTo(file + "  most-deaths:\n    item: wither_skeleton_skull\n");
  }

  @Test
  void aMissingKeyGoesUnderItsSectionWhichIsMadeIfMissing() throws Exception {
    String file = "schema-version: 1\n# Ours.\nschedule:\n  endCountdown: 2m   # short\n";
    String once = set(file, "schedule.autoEnd", "true");
    assertThat(once)
        .isEqualTo(
            "schema-version: 1\n# Ours.\nschedule:\n  endCountdown: 2m   # short\n  autoEnd: true\n");
    assertThat(set(once, "hof.entry.cooldown", "5s"))
        .isEqualTo(once + "hof:\n  entry:\n    cooldown: 5s\n");
    assertThat(set("hof:\n  entry: { bedDistance: 3 }\n", "hof.entry.cooldown", "5s"))
        .isEqualTo("hof:\n  entry: { bedDistance: 3, cooldown: 5s }\n");
    assertThat(set("hof: {}\n", "hof.entry.cooldown", "5s"))
        .isEqualTo("hof: {entry: { cooldown: 5s }}\n");
    assertThat(set("", "pets.maxPerPlayer", "3"))
        .isEqualTo("schema-version: 1\npets:\n  maxPerPlayer: 3\n");
    assertThat(set("schema-version: 1", "pets.enabled", "false"))
        .isEqualTo("schema-version: 1\npets:\n  enabled: false\n");
  }

  @Test
  void windowsLineEndingsAreKept() throws Exception {
    assertThat(set("pets:\r\n  enabled: true\r\n", "pets.maxPerPlayer", "3"))
        .isEqualTo("pets:\r\n  enabled: true\r\n  maxPerPlayer: 3\r\n");
  }

  @Test
  void whatCantBeEditedInPlaceIsRefused() {
    assertThatThrownBy(() -> set("base: &b { a: 1 }\nhof: *b\n", "hof.a", "2"))
        .isInstanceOf(YamlEdits.Refused.class)
        .hasMessageContaining("anchor or alias");
    assertThatThrownBy(() -> set("base: &b { a: 1 }\nhof:\n  <<: *b\n  c: 2\n", "hof.a", "2"))
        .isInstanceOf(YamlEdits.Refused.class);
    assertThatThrownBy(() -> set("a: 1\n---\nb: 2\n", "a", "2"))
        .hasMessageContaining("more than one YAML document");
    assertThatThrownBy(() -> set("hof: 3\n", "hof.entry", "2")).hasMessageContaining("section");
    assertThatThrownBy(() -> set("rareItems: [trident\n", "rareItems", "[]"))
        .isInstanceOfSatisfying(YamlEdits.Unreadable.class, e -> assertThat(e.line()).isPositive());
  }

  @Test
  void valuesAreWrittenAsYamlReadsThemBack() throws Exception {
    assertThat(YamlEdits.value("30s")).isEqualTo("30s");
    assertThat(YamlEdits.value("true")).isEqualTo("true");
    assertThat(YamlEdits.value(" [2d,1h] ")).isEqualTo("[2d, 1h]");
    assertThat(YamlEdits.value("archive-and-reset")).isEqualTo("archive-and-reset");
    assertThat(YamlEdits.value("'a # b'")).isEqualTo("'a # b'");
    assertThatThrownBy(() -> YamlEdits.value("{ a: 1 }")).isInstanceOf(YamlEdits.Refused.class);
    assertThatThrownBy(() -> YamlEdits.value("[unclosed")).isInstanceOf(YamlEdits.Refused.class);
  }
}
