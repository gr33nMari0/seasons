// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.store;

import static com.petepeterepeat.seasons.core.store.JournalKind.ARCHIVE_DONE;
import static com.petepeterepeat.seasons.core.store.JournalKind.ARCHIVE_STARTED;
import static com.petepeterepeat.seasons.core.store.JournalKind.CURATE_STARTED;
import static com.petepeterepeat.seasons.core.store.JournalKind.EGG_DELIVERED;
import static com.petepeterepeat.seasons.core.store.JournalKind.VAULT_SEALED;
import static com.petepeterepeat.seasons.core.store.JournalKind.VAULT_WITHDRAW;
import static com.petepeterepeat.seasons.core.store.JournalKind.VAULT_WITHDRAW_DONE;
import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.port.PlayerId;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalRecoveryTest {
  private static final PlayerId ALEX =
      new PlayerId(UUID.fromString("00000000-0000-0000-0000-00000000a1e7"));
  private static final PlayerId SAM =
      new PlayerId(UUID.fromString("00000000-0000-0000-0000-000000005a77"));
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-18T12:00:00Z"), ZoneOffset.UTC);

  @TempDir Path dir;
  private final SerialWriter writer = new SerialWriter(Runnable::run);

  private static JournalEntry entry(long sequence, JournalKind kind, PlayerId player, int season) {
    return new JournalEntry(sequence, CLOCK.instant(), kind, Optional.of(player), season, "h");
  }

  @Test
  void findsOperationsStartedButNotFinished() {
    List<OpenOperation> open =
        JournalRecovery.openOperations(
            List.of(
                entry(1, ARCHIVE_STARTED, ALEX, 2),
                entry(2, ARCHIVE_STARTED, SAM, 2),
                entry(3, VAULT_SEALED, ALEX, 2),
                entry(4, ARCHIVE_DONE, SAM, 2),
                entry(5, EGG_DELIVERED, SAM, 2),
                entry(6, CURATE_STARTED, SAM, 2)));

    assertThat(open)
        .extracting(OpenOperation::operation, OpenOperation::player, op -> op.lastStep().kind())
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(Operation.ARCHIVE, Optional.of(ALEX), VAULT_SEALED),
            org.assertj.core.groups.Tuple.tuple(
                Operation.CURATE, Optional.of(SAM), CURATE_STARTED));
  }

  @Test
  void theKeyIsPlayerAndSeason() {
    List<OpenOperation> open =
        JournalRecovery.openOperations(
            List.of(
                entry(1, ARCHIVE_STARTED, ALEX, 2),
                entry(2, ARCHIVE_DONE, ALEX, 2),
                entry(3, ARCHIVE_STARTED, ALEX, 3)));

    assertThat(open).singleElement().extracting(OpenOperation::season).isEqualTo(3);
  }

  @Test
  void vaultWithdrawalsFromOnePageAreToldApartByTheirIds() {
    List<OpenOperation> open =
        JournalRecovery.openOperations(
            List.of(
                new JournalEntry(
                    1, CLOCK.instant(), VAULT_WITHDRAW, Optional.of(ALEX), 2, "w1/before"),
                new JournalEntry(
                    2, CLOCK.instant(), VAULT_WITHDRAW, Optional.of(ALEX), 2, "w2/before"),
                new JournalEntry(
                    3, CLOCK.instant(), VAULT_WITHDRAW_DONE, Optional.of(ALEX), 2, "w2")));

    // The second withdrawal's start and finish leave the first one open.
    assertThat(open)
        .singleElement()
        .satisfies(op -> {
          assertThat(op.operation()).isEqualTo(Operation.VAULT_WITHDRAW);
          assertThat(op.instance()).isEqualTo("w1");
        });
  }

  @Test
  void aRestartedOperationKeepsOnlyItsLatestSteps() {
    List<OpenOperation> open =
        JournalRecovery.openOperations(
            List.of(
                entry(1, ARCHIVE_STARTED, ALEX, 2),
                entry(2, VAULT_SEALED, ALEX, 2),
                entry(3, ARCHIVE_STARTED, ALEX, 2)));

    assertThat(open).singleElement().satisfies(op -> assertThat(op.steps()).hasSize(1));
  }

  @Test
  void handlersFinishWaitOrFailAndPendingOnesAreRetriedAtLogin() throws Exception {
    Journal journal = Journal.open(dir.resolve("journal.log"), writer, CLOCK);
    journal.append(ARCHIVE_STARTED, ALEX, 2, "a").join();
    journal.append(ARCHIVE_STARTED, SAM, 2, "s").join();
    JournalRecovery recovery = new JournalRecovery(journal, Runnable::run);
    List<PlayerId> online = new ArrayList<>(List.of(SAM));
    recovery.register(
        Operation.ARCHIVE,
        op -> {
          PlayerId player = op.player().orElseThrow();
          if (!online.contains(player)) {
            return CompletableFuture.completedFuture(RecoveryOutcome.waitForPlayer());
          }
          return journal
              .append(ARCHIVE_DONE, player, op.season(), op.lastStep().payloadHash())
              .thenApply(entry -> RecoveryOutcome.finished());
        });

    recovery.recoverAll().join();

    assertThat(recovery.pending())
        .extracting(OpenOperation::player)
        .containsExactly(Optional.of(ALEX));
    online.add(ALEX);
    assertThat(recovery.retry(ALEX).join()).isEqualTo(1);
    assertThat(recovery.pending()).isEmpty();
    assertThat(JournalRecovery.openOperations(journal.entries())).isEmpty();
  }

  @Test
  void anOperationWithNoHandlerOrAFailingOneStaysPending() throws Exception {
    Journal journal = Journal.open(dir.resolve("journal.log"), writer, CLOCK);
    journal.append(ARCHIVE_STARTED, ALEX, 2, "a").join();
    journal.append(CURATE_STARTED, SAM, 2, "c").join();
    JournalRecovery recovery = new JournalRecovery(journal, Runnable::run);
    recovery.register(
        Operation.CURATE,
        op -> {
          throw new IllegalStateException("boom");
        });

    recovery.recoverAll().join();

    assertThat(recovery.pending()).hasSize(2);
    assertThat(recovery.failures())
        .hasSize(1)
        .containsValue("java.lang.IllegalStateException: boom");
  }
}
