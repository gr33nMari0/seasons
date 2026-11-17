// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.store;

/** What a {@link RecoveryHandler} managed for one open operation. */
public sealed interface RecoveryOutcome {
  /** The operation is finished and its finish entry written. */
  record Finished() implements RecoveryOutcome {}

  /** The operation needs its player online, such as to compare their live inventory. */
  record WaitForPlayer() implements RecoveryOutcome {}

  /** The operation can't be finished now; an admin should look. */
  record Failed(String reason) implements RecoveryOutcome {}

  static RecoveryOutcome finished() {
    return new Finished();
  }

  static RecoveryOutcome waitForPlayer() {
    return new WaitForPlayer();
  }

  static RecoveryOutcome failed(String reason) {
    return new Failed(reason);
  }
}
