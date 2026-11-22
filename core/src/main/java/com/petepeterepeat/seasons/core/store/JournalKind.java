// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.store;

/** What a journal entry records. Each is written and fsynced before the change it covers. */
public enum JournalKind {
  ARCHIVE_STARTED,
  VAULT_SEALED,
  /**
   * The player matched their copy and is about to be cleared: only after this may recovery take an
   * empty inventory as cleared (security review H3).
   */
  ARCHIVE_CLEARING,
  ARCHIVE_DONE,
  /**
   * An archive that stopped while the server ran on, put back before the player was cleared: its
   * vault page removed and its file moved aside. Closes the operation (security review H2).
   */
  ARCHIVE_ABORTED,
  UNARCHIVE_STARTED,
  UNARCHIVE_DONE,
  VAULT_WITHDRAW,
  VAULT_WITHDRAW_DONE,
  CURATE_STARTED,
  CURATE_DONE,
  IMPORT_STARTED,
  IMPORT_DONE,
  IMPORT_UNDO_STARTED,
  IMPORT_UNDO_DONE,
  EGG_DELIVERED,
  PET_TRAVELLED,
  /** A cell's gate plane set open or shut (questions 169). Needs no recovery. */
  GATE_SET,
  /** {@code /museum add} or {@code remove} started: the op's inventory hash before it. */
  MUSEUM_STARTED,
  MUSEUM_DONE,
  /** {@code /hof stamp} started: its id, with its plan saved under {@code data/stamps/}. */
  STAMP_STARTED,
  STAMP_DONE,
  /**
   * {@code /season reset} confirmed, before its first change to the world (questions 283). Needs no
   * recovery from the journal, which the reset deletes: {@code plugins/Seasons/reset-pending}
   * finishes an interrupted reset at the next start.
   */
  RESET_STARTED
}
