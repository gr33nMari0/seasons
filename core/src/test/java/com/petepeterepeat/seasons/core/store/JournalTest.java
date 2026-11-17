// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.store;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.petepeterepeat.seasons.core.port.PlayerId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalTest {
  private static final PlayerId ALEX =
      new PlayerId(UUID.fromString("00000000-0000-0000-0000-00000000a1e7"));
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-18T12:00:00Z"), ZoneOffset.UTC);

  @TempDir Path dir;
  private final SerialWriter writer = new SerialWriter(Runnable::run);

  private Path file() {
    return dir.resolve("data/journal.log");
  }

  @Test
  void appendsAreOnDiskWhenTheFutureCompletes() throws Exception {
    Journal journal = Journal.open(file(), writer, CLOCK);

    JournalEntry first = journal.append(JournalKind.ARCHIVE_STARTED, ALEX, 2, "abc123").join();
    JournalEntry second = journal.append(JournalKind.VAULT_SEALED, ALEX, 2, "def456").join();

    assertThat(first.sequence()).isEqualTo(1);
    assertThat(second.sequence()).isEqualTo(2);
    assertThat(Files.readAllLines(file())).hasSize(2);
    Journal reopened = Journal.open(file(), writer, CLOCK);
    assertThat(reopened.entries()).containsExactly(first, second);
    assertThat(reopened.health()).isEqualTo(new JournalHealth(2, 2, 0, Optional.empty()));
  }

  @Test
  void sequenceCarriesOnAfterARestart() throws Exception {
    Journal.open(file(), writer, CLOCK).append(JournalKind.EGG_DELIVERED, ALEX, 2, "x").join();

    JournalEntry next =
        Journal.open(file(), writer, CLOCK).append(JournalKind.PET_TRAVELLED, null, 2, "y").join();

    assertThat(next.sequence()).isEqualTo(2);
    assertThat(next.player()).isEmpty();
  }

  @Test
  void aLineCutShortByACrashIsDroppedAndTheFileRepaired() throws Exception {
    Journal journal = Journal.open(file(), writer, CLOCK);
    JournalEntry kept = journal.append(JournalKind.ARCHIVE_STARTED, ALEX, 2, "abc").join();
    String torn =
        Journal.line(
            new JournalEntry(
                2, CLOCK.instant(), JournalKind.VAULT_SEALED, Optional.of(ALEX), 2, "def"));
    Files.writeString(file(), Files.readString(file()) + torn.substring(0, 20), UTF_8);

    Journal reopened = Journal.open(file(), writer, CLOCK);

    assertThat(reopened.entries()).containsExactly(kept);
    assertThat(reopened.health().damagedLines()).isEqualTo(1);
    assertThat(Files.readString(file())).isEqualTo(Journal.line(kept) + "\n");
    try (Stream<Path> files = Files.list(file().getParent())) {
      assertThat(files.map(p -> p.getFileName().toString()))
          .anyMatch(n -> n.startsWith("journal.log.damaged-"));
    }
    assertThat(reopened.append(JournalKind.VAULT_SEALED, ALEX, 2, "def").join().sequence())
        .isEqualTo(2);
  }

  @Test
  void aLineWithABadChecksumIsDropped() throws Exception {
    Journal journal = Journal.open(file(), writer, CLOCK);
    journal.append(JournalKind.ARCHIVE_STARTED, ALEX, 2, "abc").join();
    journal.append(JournalKind.ARCHIVE_DONE, ALEX, 2, "abc").join();
    Files.writeString(file(), Files.readString(file()).replace("ARCHIVE_DONE", "ARCHIVE_DUNE"));

    assertThat(Journal.open(file(), writer, CLOCK).entries())
        .extracting(JournalEntry::kind)
        .containsExactly(JournalKind.ARCHIVE_STARTED);
  }

  @Test
  void compactionKeepsWhatItIsToldToAndTheSequence() throws Exception {
    Journal journal = Journal.open(file(), writer, CLOCK);
    journal.append(JournalKind.ARCHIVE_STARTED, ALEX, 2, "a").join();
    journal.append(JournalKind.ARCHIVE_DONE, ALEX, 2, "a").join();
    JournalEntry open = journal.append(JournalKind.CURATE_STARTED, ALEX, 2, "c").join();

    assertThat(journal.compact(entry -> entry.kind() == JournalKind.CURATE_STARTED).join())
        .isEqualTo(1);

    assertThat(Journal.open(file(), writer, CLOCK).entries()).containsExactly(open);
    assertThat(journal.append(JournalKind.CURATE_DONE, ALEX, 2, "c").join().sequence())
        .isEqualTo(4);
  }

  @Test
  void aWriteFailureIsReportedInHealth() throws Exception {
    Journal journal = Journal.open(file(), writer, CLOCK);
    Files.createDirectories(file());

    assertThatThrownBy(() -> journal.append(JournalKind.EGG_DELIVERED, ALEX, 2, "x").join())
        .hasRootCauseInstanceOf(java.io.IOException.class);
    assertThat(journal.health().lastError()).isPresent();
  }
}
