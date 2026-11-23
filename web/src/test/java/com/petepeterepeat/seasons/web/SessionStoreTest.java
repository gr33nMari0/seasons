// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class SessionStoreTest {
  @TempDir Path dir;
  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-23T10:00:00Z"));
  private final UUID alex = UUID.randomUUID();

  private SessionStore store(FileWriter writer) {
    return new SessionStore(
        dir.resolve("sessions.json"), writer, clock, LoggerFactory.getLogger("test"));
  }

  @Test
  void onlyTheHashIsStoredAndItSurvivesARestart() throws Exception {
    FileWriter writer = new FileWriter(LoggerFactory.getLogger("test"));
    SessionStore sessions = store(writer);
    SessionStore.Created created =
        sessions.create(alex, "Alex", "Safari on iPad", false, Duration.ofDays(30));
    writer.flush();
    String file = Files.readString(dir.resolve("sessions.json"));
    assertThat(file).doesNotContain(created.token()).contains(Tokens.hash(created.token()));
    SessionStore again = store(writer);
    again.load();
    assertThat(again.find(created.token())).map(SessionStore.Session::name).contains("Alex");
    assertThat(again.find("not-a-token")).isEmpty();
    writer.close();
  }

  @Test
  void expiresAfterItsLifetime() {
    FileWriter writer = new FileWriter(LoggerFactory.getLogger("test"));
    SessionStore sessions = store(writer);
    String token = sessions.create(alex, "Alex", "x", false, Duration.ofDays(30)).token();
    clock.advance(Duration.ofDays(29));
    assertThat(sessions.find(token)).isPresent();
    clock.advance(Duration.ofDays(1));
    assertThat(sessions.find(token)).isEmpty();
    assertThat(sessions.of(alex)).isEmpty();
    writer.close();
  }

  @Test
  void revokeOneOrAllAndTrackAdmin() {
    FileWriter writer = new FileWriter(LoggerFactory.getLogger("test"));
    SessionStore sessions = store(writer);
    SessionStore.Created ipad =
        sessions.create(alex, "Alex", "Safari on iPad", false, Duration.ofDays(1));
    SessionStore.Created pc =
        sessions.create(alex, "Alex", "Firefox on Windows", false, Duration.ofDays(1));
    assertThat(sessions.of(alex)).hasSize(2);
    assertThat(sessions.revoke(alex, pc.session().id())).isPresent();
    assertThat(sessions.find(pc.token())).isEmpty();
    assertThat(sessions.revoke(UUID.randomUUID(), ipad.session().id()))
        .as("someone else's")
        .isEmpty();
    sessions.setAdmin(alex, true);
    assertThat(sessions.find(ipad.token())).map(SessionStore.Session::admin).contains(true);
    assertThat(sessions.revokeAll(alex)).isEqualTo(1);
    assertThat(sessions.find(ipad.token())).isEmpty();
    writer.close();
  }
}
