// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.scenario;

/** The moments in archiving a scenario can stop the server at, each right after its write. */
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
  ARCHIVE_DONE
}
