// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.guard;

import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.event.IntentHandler;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.player.FurthestTracker;
import com.petepeterepeat.seasons.core.port.PlayerId;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Decides every intent: a player being archived can do nothing at all, the Hall of Fame has its say
 * on building inside it, on clicks on its anchors and figures, and on visitors coming and going, a
 * player who just arrived takes no damage, and a placed block counts towards their furthest block.
 */
public final class IntentRouter implements IntentHandler {
  private final ArchiveHold hold;
  private final Immunity immunity;
  private final FurthestTracker furthest;
  private final Function<Intent, Optional<Decision>> hall;
  private final Consumer<PlayerId> damaged;
  private final Predicate<PlayerId> admin;
  private final Supplier<Messages> messages;

  /**
   * @param hall the Hall of Fame's decision on an intent, if it has one
   * @param damaged hears about every hit a player takes that isn't refused
   * @param admin whether a player may still run commands while held, as a season admin may
   * @param messages what a held player is told
   */
  public IntentRouter(
      ArchiveHold hold,
      Immunity immunity,
      FurthestTracker furthest,
      Function<Intent, Optional<Decision>> hall,
      Consumer<PlayerId> damaged,
      Predicate<PlayerId> admin,
      Supplier<Messages> messages) {
    this.hold = hold;
    this.immunity = immunity;
    this.furthest = furthest;
    this.hall = hall;
    this.damaged = damaged;
    this.admin = admin;
    this.messages = messages;
  }

  @Override
  public Decision decide(Intent intent) {
    if (intent instanceof Intent.RunCommand command) {
      // Commands, other plugins' included, can change what a player carries between the copy and
      // the clear (security review M1); an admin still runs theirs.
      return hold.inTransaction(command.player()) && !admin.test(command.player())
          ? Decision.deny(messages.get().get("archive.hold-command"))
          : Decision.allow();
    }
    // A held player is still moved, by Seasons among others; the Hall decides about that.
    boolean movement = intent instanceof Intent.Move || intent instanceof Intent.Teleport;
    if (!movement && hold.isHeld(intent.player())) {
      return new Decision.Deny(Optional.empty());
    }
    Optional<Decision> inHall = hall.apply(intent);
    if (inHall.isPresent()) {
      // Blocks placed in the Hall never count towards the furthest block.
      return inHall.get();
    }
    return switch (intent) {
      case Intent.TakeDamage damage -> {
        if (immunity.isImmune(damage.player())) {
          yield new Decision.Deny(Optional.empty());
        }
        damaged.accept(damage.player());
        yield Decision.allow();
      }
      case Intent.PlaceBlock place -> {
        furthest.placed(place.player(), place.block());
        yield Decision.allow();
      }
      default -> Decision.allow();
    };
  }
}
