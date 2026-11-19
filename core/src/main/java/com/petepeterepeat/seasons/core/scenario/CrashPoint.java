// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.scenario;

/**
 * The moments in archiving, importing or a vault withdrawal a scenario can stop the server at, each
 * right after its write.
 */
public enum CrashPoint {
  /** The archive file is written and fsynced; the journal says nothing yet. */
  FILE_WRITTEN,
  /** {@code ARCHIVE_STARTED} is on disk. */
  ARCHIVE_STARTED,
  /** {@code VAULT_SEALED} is on disk. */
  VAULT_SEALED,
  /** The player is cleared and reset, and their file saved; {@code ARCHIVE_DONE} isn't written. */
  RESET,
  /** {@code ARCHIVE_DONE} is on disk. */
  ARCHIVE_DONE,
  /** An import's record is written and {@code IMPORT_STARTED} is on disk; the cell is untouched. */
  IMPORT_STARTED,
  /** An import emptied the cell; the gallery and {@code IMPORT_DONE} aren't written. */
  IMPORT_CLEARED,
  /** {@code VAULT_WITHDRAW} is on disk; the vault file is untouched. */
  VAULT_WITHDRAW_STARTED,
  /** The item is gone from the vault file; it hasn't been given. */
  VAULT_WITHDRAW_PERSISTED,
  /** The item is given and the player's file saved; {@code VAULT_WITHDRAW_DONE} isn't written. */
  VAULT_WITHDRAW_GIVEN
}
