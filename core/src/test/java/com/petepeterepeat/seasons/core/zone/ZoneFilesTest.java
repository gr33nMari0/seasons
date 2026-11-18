// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.zone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/** Zones and Homecoming as season files hold them, by hand or by command. */
class ZoneFilesTest {
  private static ConfigurationNode yaml(String text) throws Exception {
    return YamlConfigurationLoader.builder()
        .source(() -> new BufferedReader(new StringReader(text)))
        .build()
        .load();
  }

  @Test
  void theSpecsExampleReads() throws Exception {
    ConfigurationNode node =
        yaml(
            """
            zones:
              old-world:  { dimension: minecraft:overworld, shape: { cylinder: { center: [0, 0], radius: 12000 } }, mode: block, lockedUntil: homecoming, afterUnlock: off }
              old-nether: { dimension: minecraft:the_nether, shape: { cylinder: { center: [0, 0], radius: 1500, maxY: 127 } }, mode: block, lockedUntil: homecoming, afterUnlock: off }
            homecoming:
              unlock: { manual: true }
            """);
    Map<String, Zone> zones = ZoneFiles.zones(node.node("zones"));
    assertThat(zones).containsOnlyKeys("old-world", "old-nether");
    Zone nether = zones.get("old-nether");
    assertThat(nether.dimension()).isEqualTo("minecraft:the_nether");
    assertThat(nether.shape())
        .isEqualTo(new ZoneShape.Cylinder(0, 0, 1500, OptionalInt.empty(), OptionalInt.of(127)));
    assertThat(nether.mode()).isEqualTo(ZoneMode.BLOCK);
    assertThat(nether.afterUnlock()).isEqualTo(ZoneMode.OFF);
    assertThat(nether.lockedUntil()).isEqualTo(new UnlockCondition.Homecoming());
    assertThat(ZoneFiles.homecoming(node.node("homecoming")))
        .isEqualTo(new UnlockCondition.Manual());
  }

  @Test
  void conditionsOfEveryKindRead() throws Exception {
    ConfigurationNode node =
        yaml(
            """
            unlock: { any: [ { date: "2027-01-10T19:00+11:00" }, { all: [ { after: 21d }, { dragonKilled: true }, { playersReachedEnd: 3 } ] } ] }
            """);
    UnlockCondition read = ZoneFiles.homecoming(node);
    assertThat(read)
        .isEqualTo(
            new UnlockCondition.Any(
                List.of(
                    new UnlockCondition.At(java.time.ZonedDateTime.parse("2027-01-10T19:00+11:00")),
                    new UnlockCondition.All(
                        List.of(
                            new UnlockCondition.After(Duration.ofDays(21)),
                            new UnlockCondition.DragonKilled(),
                            new UnlockCondition.PlayersReachedEnd(3))))));
  }

  @Test
  void whatCommandsWriteReadsBackTheSame() throws Exception {
    Zone zone =
        new Zone(
            "far-build",
            "minecraft:overworld",
            ZoneShape.Box.between(9000, -64, 200, 9100, 320, 300),
            ZoneMode.NO_BUILD,
            new UnlockCondition.All(
                List.of(
                    new UnlockCondition.After(Duration.ofDays(7)),
                    new UnlockCondition.DragonKilled())),
            ZoneMode.NO_BUILD,
            List.of(ZoneShape.Box.between(9010, 60, 210, 9020, 80, 220)),
            Optional.of("<red>Keep out</red>"));
    Zone circle =
        Zone.drawn(
            "base",
            "minecraft:overworld",
            new ZoneShape.Cylinder(500, -20, 30, OptionalInt.of(-10), OptionalInt.of(80)));
    ConfigurationNode root = YamlConfigurationLoader.builder().build().createNode();
    ZoneFiles.write(root.node("zones"), zone);
    ZoneFiles.write(root.node("zones"), circle);
    StringWriter out = new StringWriter();
    YamlConfigurationLoader.builder()
        .nodeStyle(NodeStyle.BLOCK)
        .sink(() -> new BufferedWriter(out))
        .build()
        .save(root);
    // A bare off reads as false in YAML, so it is written quoted, and read back either way.
    assertThat(out.toString()).doesNotContain("afterUnlock: off\n").contains("radius: 30\n");
    Map<String, Zone> read = ZoneFiles.zones(yaml(out.toString()).node("zones"));
    assertThat(read).containsEntry("far-build", zone).containsEntry("base", circle);
  }

  @Test
  void brokenZonesSayWhere() {
    assertThatThrownBy(() ->
            ZoneFiles.zones(yaml("ring: { shape: { outside: { center: [0, 0], radius: 5 } } }")))
        .isInstanceOf(ZoneFiles.Invalid.class)
        .hasMessageContaining("outside isn't built yet")
        .extracting(e -> ((ZoneFiles.Invalid) e).path())
        .isEqualTo("zones.ring.shape");
    assertThatThrownBy(() ->
            ZoneFiles.zones(yaml("base: { shape: { cylinder: { center: [0, 0], radius: -3 } } }")))
        .isInstanceOf(ZoneFiles.Invalid.class)
        .extracting(e -> ((ZoneFiles.Invalid) e).path())
        .isEqualTo("zones.base.shape.cylinder.radius");
    assertThatThrownBy(() ->
            ZoneFiles.zones(
                yaml(
                    "base: { shape: { box: { min: [0, 0, 0], max: [1, 1, 1] } }, afterUnlock:"
                        + " block }")))
        .hasMessageContaining("off or no-build");
    assertThatThrownBy(() ->
            ZoneFiles.zones(yaml("Base: { shape: { box: { min: [0, 0, 0], max: [1, 1, 1] } } }")))
        .hasMessageContaining("lower-case");
    assertThatThrownBy(() -> ZoneFiles.homecoming(yaml("unlock: { date: tomorrow }")))
        .extracting(e -> ((ZoneFiles.Invalid) e).path())
        .isEqualTo("homecoming.unlock.date");
  }
}
