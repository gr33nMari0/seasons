// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.scenario;

/**
 * The moments in archiving, importing, a vault withdrawal, handing over the dragon egg or {@code
 * /museum add} a scenario can stop the server at, each right after its write.
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
  VAULT_WITHDRAW_GIVEN,
  /** {@code GATE_SET} is on disk; the gate's blocks haven't changed. Armed by the cell's owner. */
  GATE_SET,
  /**
   * {@code EGG_DELIVERED} is on disk and the egg is off the exit portal; it isn't on the Returned
   * page yet. Armed by the slayer.
   */
  EGG_CLEARED,
  /** The egg is on the slayer's Returned page; it hasn't been given. Armed by the slayer. */
  EGG_RETURNED,
  /**
   * {@code MUSEUM_STARTED} is on disk and the item is in the museum's store; the op still holds it.
   * Armed by the op.
   */
  MUSEUM_STORED,
  /** The item is taken from the op and their file saved; {@code MUSEUM_DONE} isn't written. */
  MUSEUM_TAKEN
}
