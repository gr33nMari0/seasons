// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading the audit log back for /season audit (questions 173). */
class AuditLogTest {
  @TempDir Path dir;

  private AuditLog log(String now) {
    return new AuditLog(
        dir, new SerialWriter(Runnable::run), Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
  }

  @Test
  void recentReadsAcrossTheMonthBoundary() {
    log("2026-10-31T23:00:00Z").record("Peter", "config", "config.yml a", "ok", "1 -> 2");
    AuditLog november = log("2026-11-01T01:00:00Z");
    november.record("Seasons", "archive", "Pat", "ok", "Season 2");

    assertThat(november.recent(Optional.empty(), 10).join())
        .extracting(AuditLog.Entry::action)
        .containsExactly("config", "archive");
    assertThat(november.recent(Optional.empty(), 1).join())
        .extracting(AuditLog.Entry::action)
        .containsExactly("archive");
  }

  @Test
  void filtersByActorOrTargetIgnoringCase() {
    AuditLog log = log("2026-11-20T12:00:00Z");
    log.record("Seasons", "archive", "Pat", "ok", "Season 2");
    log.record("pat", "claim", "season-2:c1", "claimed", "Season 2");
    log.record("Seasons", "archive", "Kim", "ok", "Season 2");

    assertThat(log.recent(Optional.of("PAT"), 10).join())
        .extracting(AuditLog.Entry::action)
        .containsExactly("archive", "claim");
  }

  @Test
  void aBrokenLineIsSkipped() throws IOException {
    AuditLog log = log("2026-11-20T12:00:00Z");
    log.record("Seasons", "archive", "Pat", "ok", "Season 2");
    Files.writeString(
        dir.resolve("2026-11.jsonl"),
        Files.readString(dir.resolve("2026-11.jsonl")) + "{\"time\":\"2026-11-20T12:0",
        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

    assertThat(log.recent(Optional.empty(), 10).join()).hasSize(1);
  }
}
