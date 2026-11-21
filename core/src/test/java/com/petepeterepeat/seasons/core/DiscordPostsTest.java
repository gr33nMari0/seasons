// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.map.MapPublisher;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.io.IOException;
import java.net.URI;
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
 * What goes to which Discord webhook: the season's end and launch to the public channel, and admin
 * alerts such as tripwire hits only to the admin webhook, never the other way round.
 */
class DiscordPostsTest {
  private static final URI PUBLIC = URI.create("https://discord.example/public");
  private static final URI ADMIN = URI.create("https://discord.example/admin");

  @TempDir Path dir;
  private final FakePlatform fake = new FakePlatform();
  private final List<Post> posts = new ArrayList<>();
  private Seasons seasons;

  private record Post(URI webhook, String content) {}

  private final Webhooks webhooks =
      new Webhooks() {
        @Override
        public void post(URI webhook, String content) {
          posts.add(new Post(webhook, content));
        }

        @Override
        public void close() {}
      };

  private void start(boolean admin) throws IOException {
    Files.createDirectories(dir.resolve("plugins/Seasons"));
    Files.writeString(
        dir.resolve("plugins/Seasons/secrets.yml"),
        "schema-version: 1\ndiscordWebhook: \""
            + PUBLIC
            + "\"\n"
            + (admin ? "adminWebhook: \"" + ADMIN + "\"\n" : ""));
    seasons =
        new Seasons(
            fake.platform(new Capabilities(EnumSet.allOf(Capability.class)), dir),
            "1.2.3",
            fake.clock,
            webhooks,
            MapPublisher.NONE,
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

  private void run(String command) {
    TestCommands.run(fake.commands.getFirst(), TestSource.console(), command);
  }

  private List<String> to(URI webhook) {
    return posts.stream().filter(p -> p.webhook().equals(webhook)).map(Post::content).toList();
  }

  @Test
  void theEndAndTheLaunchArePostedOnceEachToThePublicChannel() throws IOException {
    start(true);
    run("/season init 2");
    run("/season end --now");
    PlayerRef admin = fake.join("Admin");
    fake.player(admin).position = Position.of("minecraft:overworld", 500, -60, 300);
    TestCommands.run(
        fake.commands.getFirst(), new TestSource(Optional.of(admin.id())), "/season spawn set");
    run("/season launch");

    assertThat(to(PUBLIC))
        .containsExactly("Season 2 has ended. Welcome to the Intermission!", "Season 3 has begun!");
    assertThat(to(ADMIN)).isEmpty();
  }

  @Test
  void tripwireHitsGoOnlyToTheAdminWebhook() throws IOException {
    start(true);
    PlayerRef pat = fake.join("Pat");
    fake.tripwire.found(Optional.of(pat.id()), "in an inventory", List.of("3 × minecraft:stone"));

    assertThat(to(ADMIN))
        .containsExactly("Tripwire: Deleted 3 × minecraft:stone in an inventory (Pat)");
    assertThat(to(PUBLIC)).isEmpty();
  }

  @Test
  void withoutAnAdminWebhookAlertsPostNothing() throws IOException {
    start(false);
    PlayerRef pat = fake.join("Pat");
    fake.tripwire.found(Optional.of(pat.id()), "in an inventory", List.of("3 × minecraft:stone"));

    assertThat(posts).isEmpty();
  }
}
