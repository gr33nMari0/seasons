// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.zone;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * When a zone unlocks by itself, or Homecoming happens. The manual command always works as well,
 * whatever the condition.
 */
public sealed interface UnlockCondition {
  /** Whether the condition holds, given what has happened this season. */
  boolean met(Facts facts);

  /**
   * What has happened this season.
   *
   * @param launchedAt when the season launched, if it did
   * @param homecoming whether Homecoming has happened
   * @param playersReachedEnd how many players have arrived in the End this season
   */
  record Facts(
      Instant now,
      Optional<Instant> launchedAt,
      boolean homecoming,
      boolean dragonKilled,
      int playersReachedEnd) {}

  /**
   * Only an admin's command: {@code /season unlock homecoming} or {@code /season unlock <zone>}.
   */
  record Manual() implements UnlockCondition {
    @Override
    public boolean met(Facts facts) {
      return false;
    }
  }

  /** With Homecoming, the default for a zone. */
  record Homecoming() implements UnlockCondition {
    @Override
    public boolean met(Facts facts) {
      return facts.homecoming();
    }
  }

  /** At a fixed time, such as {@code date: 2027-01-10T19:00+11:00}. */
  record At(ZonedDateTime time) implements UnlockCondition {
    @Override
    public boolean met(Facts facts) {
      return !facts.now().isBefore(time.toInstant());
    }
  }

  /** A while after launch, such as {@code after: 21d}. */
  record After(Duration after) implements UnlockCondition {
    @Override
    public boolean met(Facts facts) {
      return facts.launchedAt().map(at -> !facts.now().isBefore(at.plus(after))).orElse(false);
    }
  }

  /** Once anyone has killed the ender dragon this season. */
  record DragonKilled() implements UnlockCondition {
    @Override
    public boolean met(Facts facts) {
      return facts.dragonKilled();
    }
  }

  /** Once this many players have arrived in the End this season. */
  record PlayersReachedEnd(int players) implements UnlockCondition {
    @Override
    public boolean met(Facts facts) {
      return facts.playersReachedEnd() >= players;
    }
  }

  /** Once any of these holds. */
  record Any(List<UnlockCondition> any) implements UnlockCondition {
    public Any {
      any = List.copyOf(any);
    }

    @Override
    public boolean met(Facts facts) {
      return any.stream().anyMatch(c -> c.met(facts));
    }
  }

  /** Once all of these hold. */
  record All(List<UnlockCondition> all) implements UnlockCondition {
    public All {
      all = List.copyOf(all);
    }

    @Override
    public boolean met(Facts facts) {
      return all.stream().allMatch(c -> c.met(facts));
    }
  }

  /** The first date this condition names, if it names one, for telling players when. */
  default Optional<ZonedDateTime> firstDate() {
    return switch (this) {
      case At at -> Optional.of(at.time());
      case Any any -> any.any().stream().flatMap(c -> c.firstDate().stream()).findFirst();
      case All all -> all.all().stream().flatMap(c -> c.firstDate().stream()).findFirst();
      default -> Optional.empty();
    };
  }
}
