// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventHubTest {
  private final EventHub hub = new EventHub(new EventHub.Limits(2, 3, 4, 3));

  private static JsonObject data(int n) {
    JsonObject o = new JsonObject();
    o.addProperty("n", n);
    return o;
  }

  @Test
  void aClientThatFallsBehindLosesItsOldest() throws Exception {
    EventHub.Client c = hub.open("a", Optional.empty(), k -> true).orElseThrow();
    for (int i = 1; i <= 5; i++) {
      hub.publish("event-feed", "join", data(i));
    }
    assertThat(c.queued()).isEqualTo(3);
    assertThat(c.next(Duration.ofMillis(10)))
        .hasValueSatisfying(f -> assertThat(f).contains("\"n\":3"));
  }

  @Test
  void onlyWhatTheClientSees() throws Exception {
    EventHub.Client c = hub.open("a", Optional.empty(), k -> !k.equals("secret")).orElseThrow();
    hub.publish("secret", "x", data(1));
    assertThat(c.next(Duration.ofMillis(10))).isEmpty();
  }

  @Test
  void limitsPerVisitorAccountAndServerAndClosingFreesAPlace() {
    UUID alex = UUID.randomUUID();
    EventHub.Client one = hub.open("a", Optional.empty(), k -> true).orElseThrow();
    assertThat(hub.open("a", Optional.empty(), k -> true)).isPresent();
    assertThat(hub.open("a", Optional.empty(), k -> true)).as("third from one visitor").isEmpty();
    hub.close(one);
    assertThat(hub.open("a", Optional.empty(), k -> true)).isPresent();
    assertThat(hub.open("b", Optional.of(alex), k -> true)).isPresent();
    assertThat(hub.open("c", Optional.of(alex), k -> true)).isPresent();
    assertThat(hub.open("d", Optional.empty(), k -> true)).as("the server's 4").isEmpty();
  }

  @Test
  void closingAllEndsEveryLoop() throws Exception {
    EventHub.Client c = hub.open("a", Optional.empty(), k -> true).orElseThrow();
    hub.closeAll();
    assertThat(c.next(Duration.ofSeconds(1))).contains(EventHub.CLOSE);
  }
}
