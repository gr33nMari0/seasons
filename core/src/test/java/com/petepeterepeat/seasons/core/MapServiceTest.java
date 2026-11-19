// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.map.MapModel;
import com.petepeterepeat.seasons.core.map.MapPublisher;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.season.SeasonState;
import com.petepeterepeat.seasons.core.season.SeasonStore;
import com.petepeterepeat.seasons.core.season.Stage;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The map is published at start and again within a second of anything on it changing (a command
 * edit, a reload, launch or Homecoming), and only then; stopping Seasons closes the publisher.
 */
class MapServiceTest {
  private static final String OVERWORLD = "minecraft:overworld";

  @TempDir Path dir;
  private final FakePlatform fake = new FakePlatform();
  private final List<MapModel> published = new ArrayList<>();
  private boolean closed;
  private Seasons seasons;

  private final MapPublisher publisher =
      new MapPublisher() {
        @Override
        public void publish(MapModel model) {
          published.add(model);
        }

        @Override
        public Status status() {
          return new Status.Published(published.size(), 1);
        }

        @Override
        public void close() {
          closed = true;
        }
      };

  private void start() {
    seasons =
        new Seasons(
            fake.platform(new Capabilities(EnumSet.allOf(Capability.class)), dir),
            "1.2.3",
            fake.clock,
            Webhooks.NONE,
            publisher,
            Map.of(),
            new SerialWriter(Runnable::run),
            Optional.empty());
    seasons.start();
  }

  @AfterEach
  void stop() {
    if (seasons != null) {
      seasons.stop();
    }
  }

  private void run(Optional<PlayerRef> player, String command) {
    TestCommands.run(fake.commands.getFirst(), new TestSource(player.map(PlayerRef::id)), command);
  }

  @Test
  void theMapIsPublishedAtStartAndAgainOnlyWhenWhatItShowsChanges() {
    start();
    assertThat(published).as("at start").hasSize(1);
    assertThat(published.getFirst().points()).isEmpty();

    fake.tick();
    run(Optional.empty(), "/season init 2");
    fake.tick();
    assertThat(published).as("the state changed, but nothing on the map").hasSize(1);

    PlayerRef alex = fake.join("Alex");
    fake.player(alex).position = Position.of(OVERWORLD, 4000.5, 64, -1999.5);
    run(Optional.of(alex), "/season spawn candidate add north-mesa");
    run(Optional.of(alex), "/season marker add hubs Spawn hub");
    fake.tick();
    assertThat(published).hasSize(2);
    assertThat(published.getLast().points())
        .extracting(MapModel.Point::label)
        .containsExactly("Spawn candidate: north mesa");
    assertThat(published.getLast().waypointSets())
        .extracting(MapModel.WaypointSet::id)
        .containsExactly("hubs");
    fake.tick();
    assertThat(published).as("nothing changed since").hasSize(2);

    seasons.stop();
    assertThat(closed).isTrue();
    seasons = null;
  }

  @Test
  void homecomingChangesTheAreasAndHandEditsAreShownOnReload() throws IOException {
    Path world = dir.resolve("world/seasons");
    Files.createDirectories(world.resolve("seasons"));
    Files.writeString(
        world.resolve("seasons/3.yml"),
        """
        schema-version: 1
        season: 3
        spawn: { dimension: minecraft:overworld, pos: [3000, 64, 0] }
        zones:
          base: { dimension: minecraft:overworld, shape: { cylinder: { center: [500, 500], radius: 30 } } }
          farm: { dimension: minecraft:overworld, shape: { box: { min: [800, 0, 800], max: [810, 100, 810] } }, afterUnlock: no-build }
        """,
        UTF_8);
    new SeasonStore(world.resolve("data/season.json"), new SerialWriter(Runnable::run))
        .save(
            SeasonState.start(3, Stage.FRONTIER, Optional.of(fake.clock.instant()))
                .withLaunchApplied())
        .join();
    start();
    assertThat(published.getLast().areas())
        .extracting(MapModel.Area::label)
        .containsExactly("base: locked until Homecoming", "farm: locked until Homecoming");
    assertThat(published.getLast().points())
        .extracting(MapModel.Point::label)
        .containsExactly("Season 3 spawn");

    run(Optional.empty(), "/season unlock homecoming");
    fake.tick();
    assertThat(published.getLast().areas())
        .extracting(MapModel.Area::label)
        .containsExactly("farm: no building");

    Files.writeString(
        world.resolve("markers.yml"),
        "schema-version: 1\nsets:\n  hubs: { Hub: { pos: [0, 70, 0] } }\n",
        UTF_8);
    run(Optional.empty(), "/season reload");
    fake.tick();
    assertThat(published.getLast().waypointSets())
        .extracting(MapModel.WaypointSet::id)
        .containsExactly("hubs");
  }
}
