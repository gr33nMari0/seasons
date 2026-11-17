// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.guard;

import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.event.IntentHandler;
import com.petepeterepeat.seasons.core.player.FurthestTracker;

/**
 * Decides every intent: a player being archived can do nothing at all, a player who just arrived
 * takes no damage, and a placed block counts towards their furthest block.
 */
public final class IntentRouter implements IntentHandler {
  private final ArchiveHold hold;
  private final Immunity immunity;
  private final FurthestTracker furthest;

  public IntentRouter(ArchiveHold hold, Immunity immunity, FurthestTracker furthest) {
    this.hold = hold;
    this.immunity = immunity;
    this.furthest = furthest;
  }

  @Override
  public Decision decide(Intent intent) {
    if (hold.isHeld(intent.player())) {
      return new Decision.Deny(java.util.Optional.empty());
    }
    return switch (intent) {
      case Intent.TakeDamage damage ->
          immunity.isImmune(damage.player())
              ? new Decision.Deny(java.util.Optional.empty())
              : Decision.allow();
      case Intent.PlaceBlock place -> {
        furthest.placed(place.player(), place.block());
        yield Decision.allow();
      }
      default -> Decision.allow();
    };
  }
}
