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

  @Test
  void pagesBackWithACursorEvenThroughEntriesAtOneInstant() {
    // Every entry at the same instant: the cursor's count keeps pages from overlapping.
    AuditLog log = log("2026-11-20T12:00:00Z");
    for (int i = 0; i < 7; i++) {
      log.record("Peter", i % 2 == 0 ? "zone-lock" : "zone-unlock", "old-base", "ok", "#" + i);
    }
    AuditLog.Page first = log.page(Optional.empty(), Optional.empty(), Optional.empty(), 3).join();
    assertThat(first.entries())
        .extracting(AuditLog.Entry::details)
        .containsExactly("#4", "#5", "#6");
    AuditLog.Page second = log.page(first.next(), Optional.empty(), Optional.empty(), 3).join();
    assertThat(second.entries())
        .extracting(AuditLog.Entry::details)
        .containsExactly("#1", "#2", "#3");
    AuditLog.Page third = log.page(second.next(), Optional.empty(), Optional.empty(), 3).join();
    assertThat(third.entries()).extracting(AuditLog.Entry::details).containsExactly("#0");
    assertThat(third.next()).as("nothing older").isEmpty();
    assertThat(AuditLog.Cursor.parse(first.next().orElseThrow().toString()))
        .isEqualTo(first.next());
    assertThat(AuditLog.Cursor.parse("nonsense")).isEmpty();
  }

  @Test
  void pagesFilterByKindAndPlayer() {
    AuditLog log = log("2026-11-20T12:00:00Z");
    log.record("Peter", "zone-lock", "old-base", "ok", "");
    log.record("Seasons", "archive", "Pat", "ok", "");
    log.record("Peter", "zone-unlock", "old-base", "ok", "");
    assertThat(
            log.page(Optional.empty(), Optional.of("ZONE"), Optional.empty(), 10).join().entries())
        .extracting(AuditLog.Entry::action)
        .containsExactly("zone-lock", "zone-unlock");
    assertThat(
            log.page(Optional.empty(), Optional.empty(), Optional.of("pat"), 10).join().entries())
        .extracting(AuditLog.Entry::action)
        .containsExactly("archive");
  }

  @Test
  void aPageReachesOlderMonths() {
    log("2026-08-10T10:00:00Z").record("Peter", "config", "a", "ok", "august");
    AuditLog november = log("2026-11-20T12:00:00Z");
    november.record("Peter", "config", "b", "ok", "november");
    AuditLog.Page now =
        november.page(Optional.empty(), Optional.empty(), Optional.empty(), 10).join();
    assertThat(now.entries()).extracting(AuditLog.Entry::details).containsExactly("november");
    assertThat(now.next()).as("older months exist").isPresent();
    Optional<AuditLog.Cursor> next = now.next();
    java.util.List<String> older = new java.util.ArrayList<>();
    for (int i = 0; i < 5 && next.isPresent(); i++) {
      AuditLog.Page p = november.page(next, Optional.empty(), Optional.empty(), 10).join();
      p.entries().forEach(e -> older.add(e.details()));
      next = p.next();
    }
    assertThat(older).containsExactly("august");
  }
}
