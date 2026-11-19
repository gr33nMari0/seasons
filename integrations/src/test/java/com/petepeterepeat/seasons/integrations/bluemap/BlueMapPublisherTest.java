// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.integrations.bluemap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.flowpowered.math.vector.Vector2i;
import com.petepeterepeat.seasons.core.map.MapModel;
import com.petepeterepeat.seasons.core.map.MapModel.Area;
import com.petepeterepeat.seasons.core.map.MapModel.Outline;
import com.petepeterepeat.seasons.core.map.MapModel.Point;
import com.petepeterepeat.seasons.core.map.MapModel.Style;
import com.petepeterepeat.seasons.core.map.MapModel.WaypointSet;
import com.petepeterepeat.seasons.core.map.MapPublisher.Status;
import com.petepeterepeat.seasons.core.port.Position;
import de.bluecolored.bluemap.api.AssetStorage;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.RenderManager;
import de.bluecolored.bluemap.api.WebApp;
import de.bluecolored.bluemap.api.markers.ExtrudeMarker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import de.bluecolored.bluemap.api.plugin.Plugin;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The publisher against a stand-in for BlueMap's API: what each map gets, publishing again after
 * BlueMap reloads, taking down what's gone, and nothing left behind once Seasons stops.
 */
class BlueMapPublisherTest {
  private static final String OVERWORLD = "minecraft:overworld";
  private static final String NETHER = "minecraft:the_nether";

  private final List<FakeBlueMap> started = new ArrayList<>();
  private final List<BlueMapPublisher> publishers = new ArrayList<>();

  @AfterEach
  void stopEverything() throws Exception {
    publishers.forEach(BlueMapPublisher::close);
    for (FakeBlueMap api : started) {
      api.stop();
    }
  }

  /**
   * BlueMap with the overworld on two maps (the world and a spawn candidate's preview), and the
   * Nether and the End on one each.
   */
  private FakeBlueMap blueMap() throws Exception {
    FakeBlueMap api =
        new FakeBlueMap(
            List.of(
                new FakeWorld(OVERWORLD, List.of("world", "preview-north")),
                new FakeWorld(NETHER, List.of("world_the_nether")),
                new FakeWorld("minecraft:the_end", List.of("world_the_end"))));
    api.start();
    started.add(api);
    return api;
  }

  /** A publisher whose platform worlds are the dimension ids themselves. */
  private BlueMapPublisher publisher() {
    BlueMapPublisher publisher = new BlueMapPublisher(dimension -> Optional.of(dimension));
    publishers.add(publisher);
    return publisher;
  }

  private static MapModel model() {
    return new MapModel(
        "Seasons",
        List.of(new Point("spawn-3", "Season 3 spawn", Position.of(OVERWORLD, 6000.5, 64, 1000.5))),
        List.of(
            new Area(
                "zone-old-nether",
                "old nether: locked until Homecoming",
                NETHER,
                new Outline.Circle(0, 0, 1500),
                List.of(new Outline.Rectangle(-10, -10, 11, 11)),
                0,
                128,
                Style.LOCKED)),
        List.of(
            new WaypointSet(
                "megaprojects",
                "megaprojects",
                List.of(
                    new Point("tower", "Tower", Position.of(OVERWORLD, 100.5, 64, 200.5)),
                    new Point("fortress", "Fortress", Position.of(NETHER, 50.5, 70, 50.5))))));
  }

  @Test
  void eachMapOfADimensionGetsWhatIsShownInIt() throws Exception {
    FakeBlueMap api = blueMap();
    BlueMapPublisher publisher = publisher();
    api.maps
        .get("world")
        .getMarkerSets()
        .put("someone-else", MarkerSet.builder().label("Theirs").build());

    publisher.publish(model());
    publisher.flush();

    for (String map : List.of("world", "preview-north")) {
      Map<String, MarkerSet> sets = api.maps.get(map).getMarkerSets();
      assertThat(sets).containsKeys("seasons", "seasons-megaprojects");
      assertThat(sets.get("seasons").getLabel()).isEqualTo("Seasons");
      assertThat(sets.get("seasons").getMarkers()).containsOnlyKeys("spawn-3");
      POIMarker spawn = (POIMarker) sets.get("seasons").get("spawn-3");
      assertThat(spawn.getLabel()).isEqualTo("Season 3 spawn");
      assertThat(spawn.getPosition().getX()).isEqualTo(6000.5);
      assertThat(sets.get("seasons-megaprojects").getMarkers()).containsOnlyKeys("tower");
    }
    assertThat(api.maps.get("world").getMarkerSets())
        .as("other plugins' sets stay")
        .containsKey("someone-else");
    Map<String, MarkerSet> nether = api.maps.get("world_the_nether").getMarkerSets();
    ExtrudeMarker zone = (ExtrudeMarker) nether.get("seasons").get("zone-old-nether");
    assertThat(zone.getShapeMinY()).isEqualTo(0f);
    assertThat(zone.getShapeMaxY()).isEqualTo(128f);
    assertThat(zone.getHoles()).hasSize(1);
    assertThat(zone.getShape().getPointCount()).isEqualTo(BlueMapPublisher.points(1500));
    assertThat(nether.get("seasons-megaprojects").getMarkers()).containsOnlyKeys("fortress");
    assertThat(api.maps.get("world_the_end").getMarkerSets()).isEmpty();
    assertThat(publisher.status()).isEqualTo(new Status.Published(4, 3));
  }

  @Test
  void itPublishesAgainWhenBlueMapReloads() throws Exception {
    FakeBlueMap first = blueMap();
    BlueMapPublisher publisher = publisher();
    publisher.publish(model());
    publisher.flush();

    // A reload: BlueMap stops its API, and starts a new one with new maps and no markers.
    first.stop();
    started.remove(first);
    assertThat(publisher.status()).isEqualTo(new Status.Waiting());
    FakeBlueMap second = blueMap();
    publisher.flush();

    assertThat(second.maps.get("world").getMarkerSets())
        .containsKeys("seasons", "seasons-megaprojects");
    assertThat(second.maps.get("world_the_nether").getMarkerSets().get("seasons").getMarkers())
        .containsOnlyKeys("zone-old-nether");
    assertThat(publisher.status()).isEqualTo(new Status.Published(4, 3));
  }

  @Test
  void aModelPublishedBeforeBlueMapStartsWaitsForIt() throws Exception {
    BlueMapPublisher publisher = publisher();
    publisher.publish(model());
    publisher.flush();
    assertThat(publisher.status()).isEqualTo(new Status.Waiting());

    FakeBlueMap api = blueMap();
    publisher.flush();
    assertThat(api.maps.get("world").getMarkerSets()).containsKey("seasons");
  }

  @Test
  void setsAndMarkersThatAreGoneComeDown() throws Exception {
    FakeBlueMap api = blueMap();
    BlueMapPublisher publisher = publisher();
    publisher.publish(model());
    publisher.flush();

    publisher.publish(
        new MapModel(
            "Seasons",
            List.of(
                new Point("spawn-3", "Season 3 spawn", Position.of(OVERWORLD, 6000.5, 64, 1000.5))),
            List.of(),
            List.of()));
    publisher.flush();

    assertThat(api.maps.get("world").getMarkerSets()).containsOnlyKeys("seasons");
    assertThat(api.maps.get("world_the_nether").getMarkerSets()).isEmpty();
    assertThat(publisher.status()).isEqualTo(new Status.Published(1, 2));
  }

  @Test
  void noNameIsEverShownAsHtml() throws Exception {
    FakeBlueMap api = blueMap();
    BlueMapPublisher publisher = publisher();
    String name = "<img src=x onerror=alert(1)> & \"Tom's\"";
    publisher.publish(
        new MapModel(
            "Seasons",
            List.of(),
            List.of(),
            List.of(
                new WaypointSet(
                    "hubs",
                    "hubs",
                    List.of(new Point("hub", name, Position.of(OVERWORLD, 0.5, 64, 0.5)))))));
    publisher.flush();

    POIMarker hub =
        (POIMarker) api.maps.get("world").getMarkerSets().get("seasons-hubs").get("hub");
    assertThat(hub.getLabel())
        .as("BlueMap escapes a label itself, and shows it as text")
        .isEqualTo("&lt;img src=x onerror=alert(1)&gt; &amp; \"Tom's\"");
    assertThat(hub.getDetail())
        .as("a detail is HTML, so Seasons escapes it")
        .isEqualTo("&lt;img src=x onerror=alert(1)&gt; &amp; &quot;Tom&#39;s&quot;");
  }

  @Test
  void closingTakesItsSetsDownAndStopsListening() throws Exception {
    FakeBlueMap api = blueMap();
    BlueMapPublisher publisher = publisher();
    publisher.publish(model());
    publisher.flush();

    publisher.close();
    publishers.remove(publisher);

    assertThat(api.maps.values()).allSatisfy(map -> assertThat(map.getMarkerSets()).isEmpty());
    api.stop();
    started.remove(api);
    FakeBlueMap again = blueMap();
    assertThat(again.maps.values())
        .as("a closed publisher doesn't hear BlueMap start again")
        .allSatisfy(map -> assertThat(map.getMarkerSets()).isEmpty());
  }

  @Test
  void aCircleKeepsWithinHalfABlockOfTheTrueOne() {
    assertThat(BlueMapPublisher.points(5)).isEqualTo(32);
    for (double radius : List.of(60.0, 1500.0, 12000.0)) {
      int points = BlueMapPublisher.points(radius);
      assertThat(radius * (1 - Math.cos(Math.PI / points)))
          .as("radius %s", radius)
          .isLessThanOrEqualTo(0.5);
    }
    assertThat(BlueMapPublisher.points(1_000_000)).isEqualTo(720);
  }

  @Test
  void anOutsideZoneIsTheWorldWithItsInsideAsAHoleAndSitsAtItsFrontier() throws Exception {
    FakeBlueMap api = blueMap();
    BlueMapPublisher publisher = publisher();
    Area frontier =
        new Area(
            "zone-frontier",
            "frontier: locked until Homecoming",
            OVERWORLD,
            new Outline.Beyond(new Outline.Circle(18450, -9120, 6000)),
            List.of(new Outline.Rectangle(-10, -10, 11, 11)),
            -64,
            320,
            Style.LOCKED);

    publisher.publish(new MapModel("Seasons", List.of(), List.of(frontier), List.of()));
    publisher.flush();

    ExtrudeMarker zone =
        (ExtrudeMarker) api.maps.get("world").getMarkerSets().get("seasons").get("zone-frontier");
    assertThat(zone.getShape().getMin().getX()).isEqualTo(-Outline.Beyond.WORLD_EDGE);
    assertThat(zone.getShape().getMax().getY()).isEqualTo(Outline.Beyond.WORLD_EDGE);
    assertThat(zone.getHoles()).hasSize(2);
    assertThat(zone.getHoles().iterator().next().getMin().getX()).isCloseTo(12450, within(1.0));
    assertThat(zone.getPosition().getX()).isEqualTo(18450);
    assertThat(zone.getPosition().getZ()).isEqualTo(-9120);
  }

  @Test
  void onlyItsOwnSetsAreSeasons() {
    Predicate<String> ours = BlueMapPublisher::ours;
    assertThat(ours)
        .accepts("seasons", "seasons-megaprojects")
        .rejects("seasonal", "other", "my-seasons");
  }

  /** A stand-in for BlueMap's API, registered as BlueMap registers its own. */
  private static final class FakeBlueMap extends BlueMapAPI {
    final Map<String, FakeMap> maps = new ConcurrentHashMap<>();
    private final List<FakeWorld> worlds;

    FakeBlueMap(List<FakeWorld> worlds) {
      this.worlds = worlds;
      for (FakeWorld world : worlds) {
        for (String id : world.mapIds) {
          FakeMap map = new FakeMap(id, world);
          world.maps.add(map);
          maps.put(id, map);
        }
      }
    }

    void start() throws Exception {
      registerInstance(this);
    }

    void stop() throws Exception {
      unregisterInstance(this);
    }

    @Override
    public RenderManager getRenderManager() {
      throw new UnsupportedOperationException();
    }

    @Override
    public WebApp getWebApp() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Plugin getPlugin() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Collection<BlueMapMap> getMaps() {
      return List.copyOf(maps.values());
    }

    @Override
    public Collection<BlueMapWorld> getWorlds() {
      return List.copyOf(worlds);
    }

    /** The world for a platform world, which here is its dimension id. */
    @Override
    public Optional<BlueMapWorld> getWorld(Object world) {
      return worlds.stream().filter(w -> w.id.equals(world)).map(w -> (BlueMapWorld) w).findFirst();
    }

    @Override
    public Optional<BlueMapMap> getMap(String id) {
      return Optional.ofNullable(maps.get(id));
    }

    @Override
    public String getBlueMapVersion() {
      return "test";
    }
  }

  // BlueMap's interfaces still declare methods it has deprecated.
  @SuppressWarnings({"deprecation", "removal"})
  private static final class FakeWorld implements BlueMapWorld {
    final String id;
    final List<String> mapIds;
    final List<BlueMapMap> maps = new ArrayList<>();

    FakeWorld(String id, List<String> mapIds) {
      this.id = id;
      this.mapIds = mapIds;
    }

    @Override
    public String getId() {
      return id;
    }

    @Override
    public Path getSaveFolder() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Collection<BlueMapMap> getMaps() {
      return List.copyOf(maps);
    }
  }

  // BlueMap's interfaces still declare methods it has deprecated.
  @SuppressWarnings({"deprecation", "removal"})
  private static final class FakeMap implements BlueMapMap {
    private final String id;
    private final BlueMapWorld world;
    private final Map<String, MarkerSet> markerSets = new ConcurrentHashMap<>();

    FakeMap(String id, BlueMapWorld world) {
      this.id = id;
      this.world = world;
    }

    @Override
    public String getId() {
      return id;
    }

    @Override
    public String getName() {
      return id;
    }

    @Override
    public BlueMapWorld getWorld() {
      return world;
    }

    @Override
    public AssetStorage getAssetStorage() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, MarkerSet> getMarkerSets() {
      return markerSets;
    }

    @Override
    public Vector2i getTileSize() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Vector2i getTileOffset() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setFrozen(boolean frozen) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isFrozen() {
      return false;
    }

    @Override
    public void setTileFilter(Predicate<Vector2i> filter) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Predicate<Vector2i> getTileFilter() {
      throw new UnsupportedOperationException();
    }
  }
}
