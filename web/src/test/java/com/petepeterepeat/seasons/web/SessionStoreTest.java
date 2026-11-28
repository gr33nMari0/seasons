// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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

  /**
   * A logout holds while the device is in use: a dashboard request looking its session up at the
   * moment the player types /web logout never brings it back, in memory or in the file, and an
   * admin check that takes the node away isn't undone by one either.
   */
  @Test
  void aLogoutHoldsWhileTheDeviceIsInUse() throws Exception {
    FileWriter writer = new FileWriter(LoggerFactory.getLogger("test"));
    SessionStore sessions = store(writer);
    UUID sam = UUID.randomUUID();
    for (int round = 0; round < 100; round++) {
      SessionStore.Created ipad =
          sessions.create(alex, "Alex", "Safari on iPad", true, Duration.ofDays(1));
      AtomicBoolean stop = new AtomicBoolean();
      List<Thread> busy = new ArrayList<>();
      for (int t = 0; t < 3; t++) {
        busy.add(
            Thread.ofPlatform()
                .start(() -> {
                  while (!stop.get()) {
                    var _ = sessions.find(ipad.token());
                  }
                }));
      }
      // Another player logging in and out, so the file is saved from another thread meanwhile.
      busy.add(
          Thread.ofPlatform()
              .start(() -> {
                // A few saves a round: each is a whole file written and synced, and the writer
                // must catch up before the file is read.
                for (int i = 0; i < 10 && !stop.get(); i++) {
                  var _ = sessions.create(sam, "Sam", "x", false, Duration.ofDays(1));
                  var _ = sessions.revokeAll(sam);
                }
              }));
      Thread.sleep(1);
      sessions.setAdmin(alex, false);
      assertThat(sessions.revokeAll(alex)).isEqualTo(1);
      Thread.sleep(1);
      stop.set(true);
      for (Thread t : busy) {
        t.join();
      }
      assertThat(sessions.find(ipad.token())).as("round %d: logged out", round).isEmpty();
      assertThat(sessions.of(alex)).as("round %d: no sessions", round).isEmpty();
      writer.flush();
      assertThat(Files.readString(dir.resolve("sessions.json")))
          .as("round %d: not in the file", round)
          .doesNotContain(ipad.session().hash());
    }
    writer.close();
  }

  @Test
  void aSessionSeenWhileAdminIsTakenAwayLosesIt() throws Exception {
    FileWriter writer = new FileWriter(LoggerFactory.getLogger("test"));
    SessionStore sessions = store(writer);
    for (int round = 0; round < 100; round++) {
      SessionStore.Created ipad =
          sessions.create(alex, "Alex", "Safari on iPad", true, Duration.ofDays(1));
      AtomicBoolean stop = new AtomicBoolean();
      List<Thread> busy = new ArrayList<>();
      for (int t = 0; t < 3; t++) {
        busy.add(
            Thread.ofPlatform()
                .start(() -> {
                  while (!stop.get()) {
                    var _ = sessions.find(ipad.token());
                  }
                }));
      }
      Thread.sleep(1);
      sessions.setAdmin(alex, false);
      Thread.sleep(1);
      stop.set(true);
      for (Thread t : busy) {
        t.join();
      }
      assertThat(sessions.find(ipad.token()))
          .as("round %d: no longer admin", round)
          .map(SessionStore.Session::admin)
          .contains(false);
      var _ = sessions.revokeAll(alex);
    }
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

  @Test
  void grantsAreKeptAndA14AdminFlagMeansEveryPart() throws Exception {
    FileWriter writer = new FileWriter(LoggerFactory.getLogger("test"));
    SessionStore sessions = store(writer);
    SessionStore.Created ipad =
        sessions.create(
            alex,
            "Alex",
            "Safari on iPad",
            Grants.of(List.of(Grants.Grant.AUDIT)),
            Duration.ofDays(1));
    writer.flush();
    String file = Files.readString(dir.resolve("sessions.json"));
    assertThat(file).contains("\"admin\":true").contains("\"grants\":[\"audit\"]");
    SessionStore again = store(writer);
    again.load();
    assertThat(again.find(ipad.token()))
        .map(SessionStore.Session::grants)
        .contains(Grants.of(List.of(Grants.Grant.AUDIT)));

    // As 1.4 wrote it: an admin flag and no grants.
    writer.flush();
    Files.writeString(
        dir.resolve("sessions.json"), file.replaceAll(",\"grants\":\\[[^\\]]*\\]", ""));
    SessionStore old = store(writer);
    old.load();
    assertThat(old.find(ipad.token())).map(SessionStore.Session::grants).contains(Grants.ALL);
    old.setGrants(alex, Grants.NONE);
    assertThat(old.find(ipad.token())).map(SessionStore.Session::admin).contains(false);
    writer.close();
  }
}
