// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.map;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.config.ConfigLoader;
import com.petepeterepeat.seasons.core.config.ConfigSnapshot;
import com.petepeterepeat.seasons.core.config.SeasonFile;
import com.petepeterepeat.seasons.core.hall.Bounds;
import com.petepeterepeat.seasons.core.hall.Gallery;
import com.petepeterepeat.seasons.core.map.MapModel.Area;
import com.petepeterepeat.seasons.core.map.MapModel.Outline;
import com.petepeterepeat.seasons.core.map.MapModel.Point;
import com.petepeterepeat.seasons.core.map.MapModel.Style;
import com.petepeterepeat.seasons.core.port.BlockInfo;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.World;
import com.petepeterepeat.seasons.core.season.SeasonState;
import com.petepeterepeat.seasons.core.season.Stage;
import com.petepeterepeat.seasons.core.zone.UnlockCondition;
import com.petepeterepeat.seasons.core.zone.Zone;
import com.petepeterepeat.seasons.core.zone.ZoneMode;
import com.petepeterepeat.seasons.core.zone.ZoneShape;
import com.petepeterepeat.seasons.core.zone.Zones;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** What the map shows for given files, season state and zones in force. */
class MapModelsTest {
  private static final String OVERWORLD = "minecraft:overworld";
  private static final String NETHER = "minecraft:the_nether";

  /** Build heights as the game has them; nothing else is asked of the world. */
  private static final World WORLD =
      new World() {
        @Override
        public boolean dimensionExists(String dimension) {
          return true;
        }

        @Override
        public Position spawn(String dimension) {
          throw new UnsupportedOperationException();
        }

        @Override
        public void setSpawn(Position position) {
          throw new UnsupportedOperationException();
        }

        @Override
        public BlockInfo block(Position position) {
          throw new UnsupportedOperationException();
        }

        @Override
        public boolean isLoaded(Position position) {
          throw new UnsupportedOperationException();
        }

        @Override
        public int minHeight(String dimension) {
          return dimension.equals(OVERWORLD) ? -64 : 0;
        }

        @Override
        public int maxHeight(String dimension) {
          return dimension.equals(OVERWORLD) ? 320 : 256;
        }

        @Override
        public CompletableFuture<Void> loadChunk(Position position) {
          throw new UnsupportedOperationException();
        }
      };

  private static ConfigSnapshot snapshot(
      List<SeasonFile> seasons, List<Gallery> galleries, Waypoints waypoints) {
    ConfigSnapshot defaults = ConfigLoader.defaults();
    Map<Integer, SeasonFile> bySeason = new java.util.HashMap<>();
    seasons.forEach(s -> bySeason.put(s.season(), s));
    Map<String, Gallery> byId = new java.util.HashMap<>();
    galleries.forEach(g -> byId.put(g.id(), g));
    return new ConfigSnapshot(
        defaults.config(),
        defaults.messages(),
        defaults.secrets(),
        bySeason,
        byId,
        Map.of(),
        waypoints);
  }

  private static SeasonFile season(
      int season, Optional<Position> spawn, Map<String, Position> candidates) {
    return new SeasonFile(
        season, Optional.empty(), spawn, Map.of(), new UnlockCondition.Manual(), candidates);
  }

  private static Optional<SeasonState> playing(int season) {
    return Optional.of(SeasonState.start(season, Stage.FRONTIER, Optional.empty()));
  }

  private static MapModel build(ConfigSnapshot snapshot, Optional<SeasonState> state) {
    return build(snapshot, state, List.of());
  }

  private static MapModel build(
      ConfigSnapshot snapshot, Optional<SeasonState> state, List<Zones.InForce> zones) {
    return MapModels.build(snapshot, state, zones, zone -> "Homecoming", WORLD);
  }

  private static Position at(double x, double y, double z) {
    return Position.of(OVERWORLD, x, y, z);
  }

  @Test
  void theSpawnsOfLaunchedSeasonsAndTheNextSeasonsCandidates() {
    ConfigSnapshot files =
        snapshot(
            List.of(
                season(3, Optional.of(at(6000, 64, 1000)), Map.of("old-pick", at(1, 2, 3))),
                season(
                    4,
                    Optional.of(at(-9000, 70, 20000)),
                    Map.of(
                        "north-mesa", at(3200, 80, -20100), "east-jungle", at(19400, 70, 4100)))),
            List.of(),
            Waypoints.NONE);

    MapModel model = build(files, playing(3));

    assertThat(model.label()).isEqualTo("Seasons");
    assertThat(model.points())
        .as("Season 4's spawn waits for its launch; Season 3's candidates are done with")
        .containsExactlyInAnyOrder(
            new Point("spawn-3", "Season 3 spawn", at(6000.5, 64, 1000.5)),
            new Point(
                "candidate-east-jungle", "Spawn candidate: east jungle", at(19400.5, 70, 4100.5)),
            new Point(
                "candidate-north-mesa", "Spawn candidate: north mesa", at(3200.5, 80, -20099.5)));
    assertThat(build(files, playing(2)).points())
        .extracting(Point::id)
        .containsExactly("candidate-old-pick");
    assertThat(build(files, Optional.empty()).points()).as("before /season init").isEmpty();
  }

  @Test
  void theHallIsWherePlainHofLandsAndEachMuseumIsShown() {
    Gallery first = Gallery.create("season-1", 1, OVERWORLD).withEntry(at(0, -60, 0));
    Gallery second =
        Gallery.create("season-2", 2, OVERWORLD)
            .withBounds(new Bounds(OVERWORLD, 20, -61, 20, 32, -50, 30))
            .withEntry(at(21, -60, 21));
    Gallery third =
        Gallery.create("season-3", 3, OVERWORLD)
            .withBounds(new Bounds(OVERWORLD, 40, -61, 20, 52, -50, 30));
    Gallery museum =
        new Gallery(
            "museum",
            Gallery.Kind.MUSEUM,
            0,
            OVERWORLD,
            Optional.of(new Bounds(OVERWORLD, 200, -61, 300, 210, -50, 310)),
            Optional.empty(),
            List.of(),
            List.of(),
            Gallery.Settings.NONE);

    MapModel model =
        build(
            snapshot(List.of(), List.of(first, second, third, museum), Waypoints.NONE), playing(3));

    assertThat(model.points())
        .as(
            "the newest season gallery with an entry; a museum with none at the middle of its floor")
        .containsExactlyInAnyOrder(
            new Point("hall", "Hall of Fame", at(21.5, -60, 21.5)),
            new Point("museum-museum", "Museum", at(205.5, -61, 305.5)));
  }

  @Test
  void zonesInForceAreAreasStyledByWhatTheyStopNow() {
    Zone base =
        Zone.drawn(
            "base",
            OVERWORLD,
            new ZoneShape.Cylinder(500, 500, 30, OptionalInt.empty(), OptionalInt.empty()));
    Zone farm =
        Zone.drawn("iron-farm", OVERWORLD, ZoneShape.Box.between(700, -64, 700, 720, 0, 720));
    Zone market =
        Zone.drawn("market", OVERWORLD, ZoneShape.Box.between(300, 0, 300, 320, 100, 320));
    Zone nether =
        Zone.drawn(
            "old-nether",
            NETHER,
            new ZoneShape.Cylinder(0, 0, 1500, OptionalInt.empty(), OptionalInt.of(127)));
    Zone open = Zone.drawn("open", OVERWORLD, ZoneShape.Box.between(0, 0, 0, 1, 1, 1));

    MapModel model =
        build(
            snapshot(List.of(), List.of(), Waypoints.NONE),
            playing(3),
            List.of(
                new Zones.InForce(base, ZoneMode.BLOCK),
                new Zones.InForce(farm, ZoneMode.NO_BUILD),
                new Zones.InForce(market, ZoneMode.ANNOUNCE),
                new Zones.InForce(nether, ZoneMode.BLOCK),
                new Zones.InForce(open, ZoneMode.OFF)));

    assertThat(model.areas())
        .as("a zone that stops nothing isn't shown")
        .containsExactly(
            new Area(
                "zone-base",
                "base: locked until Homecoming",
                OVERWORLD,
                new Outline.Circle(500, 500, 30),
                List.of(),
                -64,
                320,
                Style.LOCKED),
            new Area(
                "zone-iron-farm",
                "iron farm: no building",
                OVERWORLD,
                new Outline.Rectangle(700, 700, 721, 721),
                List.of(),
                -64,
                1,
                Style.NO_BUILD),
            new Area(
                "zone-market",
                "market: entries announced until Homecoming",
                OVERWORLD,
                new Outline.Rectangle(300, 300, 321, 321),
                List.of(),
                0,
                101,
                Style.ANNOUNCE),
            new Area(
                "zone-old-nether",
                "old nether: locked until Homecoming",
                NETHER,
                new Outline.Circle(0, 0, 1500),
                List.of(),
                0,
                128,
                Style.LOCKED));
    assertThat(model.dimensions()).containsExactly(OVERWORLD, NETHER);
  }

  @Test
  void theHallAndAZonesOwnExemptionsAreHolesInIt() {
    Zone oldWorld =
        new Zone(
            "old-world",
            OVERWORLD,
            new ZoneShape.Cylinder(0, 0, 100, OptionalInt.empty(), OptionalInt.empty()),
            ZoneMode.BLOCK,
            new UnlockCondition.Homecoming(),
            ZoneMode.OFF,
            List.of(ZoneShape.Box.between(50, 0, 50, 60, 100, 60)),
            Optional.empty());
    Gallery inside =
        Gallery.create("season-2", 2, OVERWORLD)
            .withBounds(new Bounds(OVERWORLD, -10, -61, -10, 10, -50, 10));
    // Inside the circle's square, outside the circle itself.
    Gallery corner =
        Gallery.create("season-3", 3, OVERWORLD)
            .withBounds(new Bounds(OVERWORLD, 90, -61, 90, 99, -50, 99));
    Gallery nether =
        Gallery.create("season-4", 4, NETHER).withBounds(new Bounds(NETHER, -5, 0, -5, 5, 10, 5));

    MapModel model =
        build(
            snapshot(List.of(), List.of(inside, corner, nether), Waypoints.NONE),
            playing(3),
            List.of(new Zones.InForce(oldWorld, ZoneMode.BLOCK)));

    assertThat(model.areas().getFirst().holes())
        .containsExactlyInAnyOrder(
            new Outline.Rectangle(50, 50, 61, 61), new Outline.Rectangle(-10, -10, 11, 11));
  }

  @Test
  void anOutsideZoneIsARingWithItsInsideAsAHole() {
    Zone frontier =
        new Zone(
            "frontier",
            OVERWORLD,
            new ZoneShape.OutsideCircle(3000, 0, 100),
            ZoneMode.BLOCK,
            new UnlockCondition.Homecoming(),
            ZoneMode.NO_BUILD,
            // One exemption beyond the circle, one across its edge, one beyond the ring's outer
            // edge (5,100 from the centre) and one across it.
            List.of(
                ZoneShape.Box.between(3200, 0, 0, 3210, 100, 10),
                ZoneShape.Box.between(3095, 0, 0, 3105, 100, 10),
                ZoneShape.Box.between(9000, 0, 0, 9010, 100, 10),
                ZoneShape.Box.between(8095, 0, 0, 8105, 100, 10)),
            Optional.empty());
    Zone edge = Zone.drawn("edge", OVERWORLD, new ZoneShape.OutsideBox(-500, -500, 500, 500));
    // The Hall at 0, 0 lies beyond the frontier, and inside the edge's box.
    Gallery hall =
        Gallery.create("season-2", 2, OVERWORLD)
            .withBounds(new Bounds(OVERWORLD, -10, -61, -10, 10, -50, 10));

    MapModel model =
        build(
            snapshot(List.of(), List.of(hall), Waypoints.NONE),
            playing(3),
            List.of(
                new Zones.InForce(frontier, ZoneMode.BLOCK),
                new Zones.InForce(edge, ZoneMode.NO_BUILD)));

    assertThat(model.areas())
        .containsExactly(
            new Area(
                "zone-frontier",
                "frontier: locked until Homecoming",
                OVERWORLD,
                new Outline.Beyond(
                    new Outline.Circle(3000, 0, 100), new Outline.Circle(3000, 0, 5100)),
                List.of(
                    new Outline.Rectangle(3200, 0, 3211, 11),
                    new Outline.Rectangle(-10, -10, 11, 11)),
                -64,
                320,
                Style.LOCKED),
            new Area(
                "zone-edge",
                "edge: no building",
                OVERWORLD,
                new Outline.Beyond(
                    new Outline.Rectangle(-500, -500, 501, 501),
                    new Outline.Rectangle(-5500, -5500, 5501, 5501)),
                List.of(),
                -64,
                320,
                Style.NO_BUILD));
  }

  @Test
  void aRingReachesTwiceTheRadiusBeyondItsInsideAndAtLeast5000Blocks() {
    assertThat(Outline.Beyond.around(new Outline.Circle(18450, -9120, 6000)).outer())
        .isEqualTo(new Outline.Circle(18450, -9120, 18000));
    assertThat(Outline.Beyond.around(new Outline.Circle(0, 0, 1000)).outer())
        .isEqualTo(new Outline.Circle(0, 0, 6000));
    // A rectangle's radius is half its longer side.
    assertThat(Outline.Beyond.around(new Outline.Rectangle(0, 0, 8000, 2000)).outer())
        .isEqualTo(new Outline.Rectangle(-8000, -8000, 16000, 10000));
    assertThat(Outline.Beyond.around(new Outline.Rectangle(0, 0, 10, 10)).outer())
        .isEqualTo(new Outline.Rectangle(-5000, -5000, 5010, 5010));
  }

  @Test
  void heightZonesAreNotShown() {
    MapModel model =
        build(
            snapshot(List.of(), List.of(), Waypoints.NONE),
            playing(3),
            List.of(
                new Zones.InForce(
                    Zone.drawn("sky", OVERWORLD, new ZoneShape.YAbove(200)), ZoneMode.BLOCK),
                new Zones.InForce(
                    Zone.drawn("roof", NETHER, new ZoneShape.YBelow(127)), ZoneMode.BLOCK)));

    assertThat(model.areas()).isEmpty();
  }

  @Test
  void waypointSetsAreShownByNameAndTheSameFilesMakeAnEqualModel() {
    Waypoints waypoints =
        new Waypoints(
            Map.of(
                "megaprojects",
                List.of(
                    new Waypoint("Peter's Tower", at(100, 64, -200)),
                    new Waypoint("Azalea Dome", at(-300, 70, 40)))));
    ConfigSnapshot files = snapshot(List.of(), List.of(), waypoints);

    MapModel model = build(files, Optional.empty());

    assertThat(model.waypointSets())
        .singleElement()
        .satisfies(set -> {
          assertThat(set.id()).isEqualTo("megaprojects");
          assertThat(set.label()).isEqualTo("megaprojects");
          assertThat(set.points())
              .containsExactly(
                  new Point("azalea dome", "Azalea Dome", at(-299.5, 70, 40.5)),
                  new Point("peter's tower", "Peter's Tower", at(100.5, 64, -199.5)));
        });
    assertThat(build(files, Optional.empty())).isEqualTo(model);
  }
}
