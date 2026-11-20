// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.season;

import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What this season remembers of the ender dragon, kept in {@code season.json} (questions 178 to
 * 186).
 *
 * @param fight the dragon being fought and what each player has dealt it, until this season's first
 *     kill
 * @param kill this season's first kill, once it happened
 * @param flagPending whether launch couldn't clear the previously-killed flag yet, because the End
 *     wasn't loaded; it is cleared when the End loads
 */
public record DragonState(Optional<Fight> fight, Optional<Kill> kill, boolean flagPending) {
  /** A season whose dragon nobody has touched. */
  public static final DragonState NONE = new DragonState(Optional.empty(), Optional.empty(), false);

  /**
   * One dragon's fight.
   *
   * @param dealt each player's damage, in the order they first hit it
   */
  public record Fight(UUID dragon, List<Dealt> dealt) {
    public Fight {
      dealt = List.copyOf(dealt);
    }

    /** The same fight with a player's hit added. */
    public Fight hit(PlayerId player, double amount) {
      List<Dealt> next = new ArrayList<>(dealt);
      for (int i = 0; i < next.size(); i++) {
        if (next.get(i).player().equals(player)) {
          next.set(i, new Dealt(player, next.get(i).amount() + amount));
          return new Fight(dragon, next);
        }
      }
      next.add(new Dealt(player, amount));
      return new Fight(dragon, next);
    }

    /** Whoever dealt the most, the first of them to hit on a tie; empty if nobody hit it. */
    public Optional<PlayerId> mostDamage() {
      Dealt best = null;
      for (Dealt d : dealt) {
        if (best == null || d.amount() > best.amount()) {
          best = d;
        }
      }
      return Optional.ofNullable(best).map(Dealt::player);
    }
  }

  /** What one player has dealt. */
  public record Dealt(PlayerId player, double amount) {}

  /**
   * This season's first kill.
   *
   * @param slayer the slayer, or empty if no player had a hand in it
   * @param name the slayer's name then
   * @param eggBefore an egg already on the exit portal when the dragon died, left alone
   * @param eggAt where the egg being handed over stood, once Seasons is taking it
   */
  public record Kill(
      Optional<PlayerId> slayer,
      String name,
      Instant at,
      Egg egg,
      Optional<Position> eggBefore,
      Optional<Position> eggAt) {
    public Kill withEgg(Egg next, Optional<Position> where) {
      return new Kill(slayer, name, at, next, eggBefore, where);
    }
  }

  /** Where the season's egg is, as far as Seasons knows. */
  public enum Egg {
    /** Watching the exit portal for it. */
    WAITING,
    /** Taking it off the portal; the journal has {@code EGG_DELIVERED}. */
    TAKING,
    /** On the slayer's Returned page. */
    RETURNED,
    /** In the slayer's inventory. */
    GIVEN,
    /** Left on the portal: nobody slew the dragon, or {@code dragon.eggTo} is {@code portal}. */
    PORTAL,
    /** None came: the flag was set, as for any later kill. */
    NONE,
    /** None appeared in time. */
    MISSING
  }

  public DragonState withFight(Optional<Fight> next) {
    return new DragonState(next, kill, flagPending);
  }

  public DragonState withKill(Kill next) {
    return new DragonState(Optional.empty(), Optional.of(next), flagPending);
  }

  public DragonState withFlagPending(boolean pending) {
    return new DragonState(fight, kill, pending);
  }
}
