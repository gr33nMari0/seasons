// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.store;

/**
 * Finishes one kind of open operation after a crash. Called on the server thread, at startup and
 * again whenever the operation's player logs in or an admin runs {@code /season reprocess}.
 */
@FunctionalInterface
public interface RecoveryHandler {
  /**
   * Decides and does what the open operation still needs, writing its finish entry when done. Must
   * be harmless to call again for the same operation.
   */
  RecoveryOutcome recover(OpenOperation operation);
}
