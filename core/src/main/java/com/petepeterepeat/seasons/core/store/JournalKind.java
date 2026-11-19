// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.store;

/** What a journal entry records. Each is written and fsynced before the change it covers. */
public enum JournalKind {
  ARCHIVE_STARTED,
  VAULT_SEALED,
  ARCHIVE_DONE,
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
  PET_TRAVELLED
}
