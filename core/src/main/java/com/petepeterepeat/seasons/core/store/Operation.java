// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.store;

import java.util.Optional;

/**
 * A multi-step change the journal brackets with a start and a finish entry. A start with no finish
 * after it is an open operation that crash recovery picks up.
 */
public enum Operation {
  /** Archiving one player for one season; {@code VAULT_SEALED} is its middle step. */
  ARCHIVE(JournalKind.ARCHIVE_STARTED, JournalKind.ARCHIVE_DONE),
  /** {@code /season unarchive}: one archive restored to its player. */
  UNARCHIVE(JournalKind.UNARCHIVE_STARTED, JournalKind.UNARCHIVE_DONE),
  /** A curator's change to one archive. */
  CURATE(JournalKind.CURATE_STARTED, JournalKind.CURATE_DONE);

  private final JournalKind started;
  private final JournalKind finished;

  Operation(JournalKind started, JournalKind finished) {
    this.started = started;
    this.finished = finished;
  }

  public JournalKind started() {
    return started;
  }

  public JournalKind finished() {
    return finished;
  }

  /**
   * The operation an entry belongs to; single-entry kinds such as {@code EGG_DELIVERED} have none.
   */
  public static Optional<Operation> of(JournalKind kind) {
    return switch (kind) {
      case ARCHIVE_STARTED, VAULT_SEALED, ARCHIVE_DONE -> Optional.of(ARCHIVE);
      case UNARCHIVE_STARTED, UNARCHIVE_DONE -> Optional.of(UNARCHIVE);
      case CURATE_STARTED, CURATE_DONE -> Optional.of(CURATE);
      case VAULT_WITHDRAW, EGG_DELIVERED, PET_TRAVELLED -> Optional.empty();
    };
  }
}
