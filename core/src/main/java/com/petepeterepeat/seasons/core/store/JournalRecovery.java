// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.store;

import com.petepeterepeat.seasons.core.port.PlayerId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Replays the journal: finds every operation started but not finished and hands it to the handler
 * registered for its kind. Operations a handler can't finish yet stay pending and are retried when
 * their player logs in.
 *
 * <p>Runs on the server thread, because handlers change the world.
 */
public final class JournalRecovery {
  private static final Logger LOG = LoggerFactory.getLogger(JournalRecovery.class);

  private final Journal journal;
  private final Map<Operation, RecoveryHandler> handlers = new EnumMap<>(Operation.class);
  private final List<OpenOperation> pending = new ArrayList<>();
  private final Map<OpenOperation, String> failures = new LinkedHashMap<>();

  public JournalRecovery(Journal journal) {
    this.journal = journal;
  }

  /** Registers the handler for one kind of operation, before {@link #recoverAll}. */
  public void register(Operation operation, RecoveryHandler handler) {
    handlers.put(operation, handler);
  }

  /** Every open operation in a list of entries, in the order they started. Pure. */
  public static List<OpenOperation> openOperations(List<JournalEntry> entries) {
    record Key(Operation operation, Optional<PlayerId> player, int season) {}
    Map<Key, List<JournalEntry>> open = new LinkedHashMap<>();
    for (JournalEntry entry : entries) {
      Optional<Operation> operation = Operation.of(entry.kind());
      if (operation.isEmpty()) {
        continue;
      }
      Key key = new Key(operation.get(), entry.player(), entry.season());
      if (entry.kind() == key.operation().started()) {
        open.remove(key);
        open.put(key, new ArrayList<>(List.of(entry)));
      } else if (entry.kind() == key.operation().finished()) {
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
  public void recoverAll() {
    pending.clear();
    failures.clear();
    List<OpenOperation> open = openOperations(journal.entries());
    if (!open.isEmpty()) {
      LOG.info("Journal recovery: {} open operation(s)", open.size());
    }
    for (OpenOperation operation : open) {
      attempt(operation);
    }
  }

  /** When a player logs in, or for {@code /season reprocess}: retries their pending operations. */
  public void retry(PlayerId player) {
    List<OpenOperation> theirs =
        pending.stream().filter(op -> op.player().equals(Optional.of(player))).toList();
    pending.removeAll(theirs);
    theirs.forEach(failures::remove);
    theirs.forEach(this::attempt);
  }

  /** Operations still open after the last attempt. */
  public List<OpenOperation> pending() {
    return List.copyOf(pending);
  }

  /** Why each failed operation failed, for admins. */
  public Map<OpenOperation, String> failures() {
    return Map.copyOf(failures);
  }

  private void attempt(OpenOperation operation) {
    RecoveryHandler handler = handlers.get(operation.operation());
    if (handler == null) {
      LOG.warn("No recovery handler for {}; it stays pending", describe(operation));
      pending.add(operation);
      return;
    }
    RecoveryOutcome outcome;
    try {
      outcome = handler.recover(operation);
    } catch (RuntimeException e) {
      LOG.error("Recovering {} failed", describe(operation), e);
      outcome = RecoveryOutcome.failed(e.toString());
    }
    switch (outcome) {
      case RecoveryOutcome.Finished finished ->
          LOG.info("Journal recovery finished {}", describe(operation));
      case RecoveryOutcome.WaitForPlayer waiting -> pending.add(operation);
      case RecoveryOutcome.Failed failed -> {
        pending.add(operation);
        failures.put(operation, failed.reason());
        LOG.error("Journal recovery couldn't finish {}: {}", describe(operation), failed.reason());
      }
    }
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
