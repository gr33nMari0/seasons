// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.guard;

import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.event.IntentHandler;
import com.petepeterepeat.seasons.core.player.FurthestTracker;
import java.util.Optional;
import java.util.function.Function;

/**
 * Decides every intent: a player being archived can do nothing at all, the Hall of Fame has its say
 * on building inside it and on clicks on its anchors and figures, a player who just arrived takes
 * no damage, and a placed block counts towards their furthest block.
 */
public final class IntentRouter implements IntentHandler {
  private final ArchiveHold hold;
  private final Immunity immunity;
  private final FurthestTracker furthest;
  private final Function<Intent, Optional<Decision>> hall;

  /**
   * @param hall the Hall of Fame's decision on an intent, if it has one
   */
  public IntentRouter(
      ArchiveHold hold,
      Immunity immunity,
      FurthestTracker furthest,
      Function<Intent, Optional<Decision>> hall) {
    this.hold = hold;
    this.immunity = immunity;
    this.furthest = furthest;
    this.hall = hall;
  }

  @Override
  public Decision decide(Intent intent) {
    if (hold.isHeld(intent.player())) {
      return new Decision.Deny(Optional.empty());
    }
    Optional<Decision> inHall = hall.apply(intent);
    if (inHall.isPresent()) {
      // Blocks placed in the Hall never count towards the furthest block.
      return inHall.get();
    }
    return switch (intent) {
      case Intent.TakeDamage damage ->
          immunity.isImmune(damage.player())
              ? new Decision.Deny(Optional.empty())
              : Decision.allow();
      case Intent.PlaceBlock place -> {
        furthest.placed(place.player(), place.block());
        yield Decision.allow();
      }
      default -> Decision.allow();
    };
  }
}
