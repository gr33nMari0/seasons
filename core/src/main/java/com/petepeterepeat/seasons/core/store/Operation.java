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
  CURATE(JournalKind.CURATE_STARTED, JournalKind.CURATE_DONE),
  /** {@code /hof import}: one Season 1 cell turned into its owner's record. */
  IMPORT(JournalKind.IMPORT_STARTED, JournalKind.IMPORT_DONE),
  /** {@code /hof import undo}: one imported cell's items put back where they were. */
  IMPORT_UNDO(JournalKind.IMPORT_UNDO_STARTED, JournalKind.IMPORT_UNDO_DONE),
  /**
   * One item taken out of an ender vault: removed from the vault file, then given; the season is
   * the vault page's, 0 for the Returned page.
   */
  VAULT_WITHDRAW(JournalKind.VAULT_WITHDRAW, JournalKind.VAULT_WITHDRAW_DONE);

  /**
   * What tells two of this operation for the same player and season apart: a vault withdrawal's id,
   * the start of each of its entries' payload, since a player makes many withdrawals from one page;
   * empty for the rest, which run once per player and season.
   */
  public String instance(JournalEntry entry) {
    return this == VAULT_WITHDRAW ? entry.payloadHash().split("/", 2)[0] : "";
  }

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
      case IMPORT_STARTED, IMPORT_DONE -> Optional.of(IMPORT);
      case IMPORT_UNDO_STARTED, IMPORT_UNDO_DONE -> Optional.of(IMPORT_UNDO);
      case VAULT_WITHDRAW, VAULT_WITHDRAW_DONE -> Optional.of(VAULT_WITHDRAW);
      case EGG_DELIVERED, PET_TRAVELLED, GATE_SET -> Optional.empty();
    };
  }
}
