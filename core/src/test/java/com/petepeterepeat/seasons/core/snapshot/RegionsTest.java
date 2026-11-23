// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.api.OnlinePlayer;
import com.petepeterepeat.seasons.core.map.Waypoint;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.zone.Zone;
import com.petepeterepeat.seasons.core.zone.ZoneShape;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class RegionsTest {
  private static final String OVERWORLD = "minecraft:overworld";
  private final Map<String, List<Waypoint>> waypoints =
      Map.of(
          "hubs",
          List.of(
              new Waypoint("Big Tree", Position.of(OVERWORLD, 100, 64, 100)),
              new Waypoint("Nether Hub", Position.of("minecraft:the_nether", 0, 64, 0))));
  private final List<Zone> zones =
      List.of(
          Zone.drawn(
              "old-world",
              OVERWORLD,
              new ZoneShape.Cylinder(1000, 1000, 50, OptionalInt.empty(), OptionalInt.empty())),
          Zone.drawn("market", OVERWORLD, new ZoneShape.Box(-500, -64, -500, -400, 300, -400)));

  private OnlinePlayer.Region at(double x, double z) {
    return Regions.of(Position.of(OVERWORLD, x, 64, z), "minecraft:plains", waypoints, zones);
  }

  @Test
  void theBiomeFarFromAnywhereNamed() {
    assertThat(at(5000, 5000)).isEqualTo(new OnlinePlayer.Region("biome", "minecraft:plains"));
  }

  @Test
  void aWaypointWithin200Blocks() {
    assertThat(at(250, 100)).isEqualTo(new OnlinePlayer.Region("waypoint", "Big Tree"));
    assertThat(at(301, 100).kind()).isEqualTo("biome");
  }

  @Test
  void insideOrNearAZone() {
    assertThat(at(1000, 1000)).isEqualTo(new OnlinePlayer.Region("zone", "old world"));
    assertThat(at(1240, 1000)).isEqualTo(new OnlinePlayer.Region("zone", "old world"));
    assertThat(at(-300, -450)).isEqualTo(new OnlinePlayer.Region("zone", "market"));
    assertThat(at(1300, 1000).kind()).isEqualTo("biome");
  }

  @Test
  void theNearestWinsAndOtherDimensionsDontCount() {
    assertThat(
            Regions.of(
                Position.of("minecraft:the_nether", 10, 64, 10),
                "minecraft:nether_wastes",
                waypoints,
                zones))
        .isEqualTo(new OnlinePlayer.Region("waypoint", "Nether Hub"));
  }
}
