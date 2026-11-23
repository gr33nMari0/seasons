// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.store;

import com.petepeterepeat.seasons.core.log.SeasonsLog;
import com.petepeterepeat.seasons.core.port.PlayerId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import org.slf4j.Logger;

/**
 * Replays the journal: finds every operation started but not finished and hands it to the handler
 * registered for its kind. Operations a handler can't finish yet stay pending and are retried when
 * their player logs in.
 *
 * <p>Runs on the server thread, because handlers change the world.
 */
public final class JournalRecovery {
  private static final Logger LOG = SeasonsLog.get();

  private final Journal journal;
  private final Executor serverThread;
  private final Map<Operation, RecoveryHandler> handlers = new EnumMap<>(Operation.class);
  private final List<OpenOperation> pending = new CopyOnWriteArrayList<>();
  private final Map<OpenOperation, String> failures = new ConcurrentHashMap<>();
  private final Set<OpenOperation> running = ConcurrentHashMap.newKeySet();
  private final Reporter reporter;

  /** Hears how each attempt at an open operation ended, on the server thread. */
  @FunctionalInterface
  public interface Reporter {
    Reporter NONE = (operation, outcome) -> {};

    void attempted(OpenOperation operation, RecoveryOutcome outcome);
  }

  /**
   * @param serverThread where outcomes are handled, so the pending list changes on one thread
   */
  public JournalRecovery(Journal journal, Executor serverThread) {
    this(journal, serverThread, Reporter.NONE);
  }

  /**
   * @param reporter told how each attempt ended, such as for the admin webhook
   */
  public JournalRecovery(Journal journal, Executor serverThread, Reporter reporter) {
    this.journal = journal;
    this.serverThread = serverThread;
    this.reporter = reporter;
  }

  /** Registers the handler for one kind of operation, before {@link #recoverAll}. */
  public void register(Operation operation, RecoveryHandler handler) {
    handlers.put(operation, handler);
  }

  /** Every open operation in a list of entries, in the order they started. Pure. */
  public static List<OpenOperation> openOperations(List<JournalEntry> entries) {
    record Key(Operation operation, Optional<PlayerId> player, int season, String instance) {}
    Map<Key, List<JournalEntry>> open = new LinkedHashMap<>();
    for (JournalEntry entry : entries) {
      Optional<Operation> operation = Operation.of(entry.kind());
      if (operation.isEmpty()) {
        continue;
      }
      Key key =
          new Key(operation.get(), entry.player(), entry.season(), operation.get().instance(entry));
      if (entry.kind() == key.operation().started()) {
        open.remove(key);
        open.put(key, new ArrayList<>(List.of(entry)));
      } else if (key.operation().closes(entry.kind())) {
        open.remove(key);
      } else if (open.containsKey(key)) {
        open.get(key).add(entry);
      }
    }
    List<OpenOperation> result = new ArrayList<>();
    open.forEach((key, steps) ->
        result.add(new OpenOperation(key.operation(), key.player(), key.season(), steps)));
    return result;
  }

  /** At startup: tries every open operation once. */
  public CompletableFuture<Void> recoverAll() {
    pending.clear();
    failures.clear();
    List<OpenOperation> open = openOperations(journal.entries());
    if (!open.isEmpty()) {
      LOG.info("Journal recovery: {} open operation(s)", open.size());
    }
    return CompletableFuture.allOf(
        open.stream().map(this::attempt).toArray(CompletableFuture[]::new));
  }

  /**
   * When a player logs in, or for {@code /season reprocess}: retries their open operations,
   * including any the journal shows that this session hasn't tried yet.
   *
   * @return how many finished
   */
  public CompletableFuture<Integer> retry(PlayerId player) {
    Optional<PlayerId> who = Optional.of(player);
    List<OpenOperation> theirs =
        openOperations(journal.entries()).stream()
            .filter(op -> op.player().equals(who))
            .filter(op -> running.stream().noneMatch(r -> sameKey(r, op)))
            .toList();
    pending.removeIf(op -> op.player().equals(who));
    failures.keySet().removeIf(op -> op.player().equals(who));
    List<CompletableFuture<Boolean>> attempts = theirs.stream().map(this::attempt).toList();
    return CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new))
        .thenApply(done -> (int) attempts.stream().filter(CompletableFuture::join).count());
  }

  /** Operations still open after the last attempt. */
  public List<OpenOperation> pending() {
    return List.copyOf(pending);
  }

  /**
   * The operations open in the journal right now for one player. Worked out once each time the
   * journal changes, not on every call, since the server thread asks often (security review M2).
   */
  public List<OpenOperation> open(PlayerId player) {
    return index().getOrDefault(player, List.of());
  }

  private long indexedSequence = -1;
  private int indexedSize = -1;
  private Map<PlayerId, List<OpenOperation>> index = Map.of();

  private synchronized Map<PlayerId, List<OpenOperation>> index() {
    JournalHealth health = journal.health();
    if (health.lastSequence() != indexedSequence || health.entries() != indexedSize) {
      Map<PlayerId, List<OpenOperation>> next = new java.util.HashMap<>();
      for (OpenOperation op : openOperations(journal.entries())) {
        op.player().ifPresent(who -> next.computeIfAbsent(who, k -> new ArrayList<>()).add(op));
      }
      next.replaceAll((who, ops) -> List.copyOf(ops));
      index = Map.copyOf(next);
      indexedSequence = health.lastSequence();
      indexedSize = health.entries();
    }
    return index;
  }

  /** Why each failed operation failed, for admins. */
  public Map<OpenOperation, String> failures() {
    return Map.copyOf(failures);
  }

  /** Completes with whether the operation finished. */
  private CompletableFuture<Boolean> attempt(OpenOperation operation) {
    RecoveryHandler handler = handlers.get(operation.operation());
    if (handler == null) {
      LOG.warn("No recovery handler for {}; it stays pending", describe(operation));
      pending.add(operation);
      return CompletableFuture.completedFuture(false);
    }
    running.add(operation);
    CompletableFuture<RecoveryOutcome> outcome;
    try {
      outcome = handler.recover(operation);
    } catch (RuntimeException e) {
      outcome = CompletableFuture.failedFuture(e);
    }
    return outcome.handleAsync(
        (result, failure) -> {
          running.remove(operation);
          RecoveryOutcome settled = result;
          if (failure != null) {
            LOG.error("Recovering {} failed", describe(operation), failure);
            settled = RecoveryOutcome.failed(String.valueOf(failure));
          }
          try {
            reporter.attempted(operation, settled);
          } catch (RuntimeException e) {
            LOG.warn("Reporting a recovery attempt failed", e);
          }
          switch (settled) {
            case RecoveryOutcome.Finished finished -> {
              LOG.info("Journal recovery finished {}", describe(operation));
              return true;
            }
            case RecoveryOutcome.WaitForPlayer waiting -> pending.add(operation);
            case RecoveryOutcome.Failed failed -> {
              pending.add(operation);
              failures.put(operation, failed.reason());
              LOG.error(
                  "Journal recovery couldn't finish {}: {}", describe(operation), failed.reason());
            }
          }
          return false;
        },
        serverThread);
  }

  private static boolean sameKey(OpenOperation a, OpenOperation b) {
    return a.operation() == b.operation()
        && a.player().equals(b.player())
        && a.season() == b.season()
        && a.instance().equals(b.instance());
  }

  private static String describe(OpenOperation operation) {
    return operation.operation()
        + " for "
        + operation.player().map(PlayerId::toString).orElse("nobody")
        + " in season "
        + operation.season()
        + " (last step "
        + operation.lastStep().kind()
        + ")";
  }
}
