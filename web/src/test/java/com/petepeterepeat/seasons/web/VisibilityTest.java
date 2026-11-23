// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VisibilityTest {
  private final Viewer visitor = Viewer.ANONYMOUS;
  private final Viewer player = new Viewer.Player(UUID.randomUUID(), "Alex", false, "abc");
  private final Viewer admin = new Viewer.Player(UUID.randomUUID(), "Op", true, "def");

  @Test
  void theSpecsAccessLevelsByDefault() {
    Visibility v = new Visibility(Map.of(), true, new ArrayList<>());
    for (String key :
        List.of("season", "online", "online.region", "event-feed", "hall", "waypoints", "links")) {
      assertThat(v.allows(key, visitor)).as(key).isTrue();
    }
    assertThat(v.allows("leaderboards", visitor)).isFalse();
    assertThat(v.allows("leaderboards", player)).isTrue();
    assertThat(v.allows("profiles", visitor)).isFalse();
    assertThat(v.allows("hall.gear", visitor)).isFalse();
    assertThat(v.allows("server-health", player)).isFalse();
    assertThat(v.allows("server-health", admin)).isTrue();
  }

  @Test
  void webYmlChangesThemAndAFieldFollowsItsWidget() {
    List<String> problems = new ArrayList<>();
    Visibility v =
        new Visibility(
            Map.of(
                "online.region",
                "hidden",
                "online",
                "players",
                "leaderboards",
                "public",
                "bogus",
                "x"),
            true,
            problems);
    assertThat(v.allows("online.region", admin)).isFalse();
    assertThat(v.shown("online.region")).isFalse();
    assertThat(v.allows("online.dimension", visitor)).as("its widget needs a login").isFalse();
    assertThat(v.allows("leaderboards", visitor)).isTrue();
    assertThat(problems).anyMatch(p -> p.contains("bogus"));
  }

  @Test
  void closedRaisesPublicToPlayers() {
    Visibility v = new Visibility(Map.of(), false, new ArrayList<>());
    assertThat(v.allows("season", visitor)).isFalse();
    assertThat(v.allows("season", player)).isTrue();
  }
}
