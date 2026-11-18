// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.zone;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** When each kind of unlock condition holds. */
class UnlockConditionsTest {
  private static final Instant LAUNCH = Instant.parse("2026-12-01T00:00:00Z");

  private static UnlockCondition.Facts facts(
      Duration sinceLaunch, boolean homecoming, boolean dragon, int inTheEnd) {
    return new UnlockCondition.Facts(
        LAUNCH.plus(sinceLaunch), Optional.of(LAUNCH), homecoming, dragon, inTheEnd);
  }

  @Test
  void manualNeverHoldsByItself() {
    assertThat(new UnlockCondition.Manual().met(facts(Duration.ofDays(400), true, true, 9)))
        .isFalse();
  }

  @Test
  void homecomingHoldsOnceItHappens() {
    UnlockCondition homecoming = new UnlockCondition.Homecoming();
    assertThat(homecoming.met(facts(Duration.ZERO, false, true, 3))).isFalse();
    assertThat(homecoming.met(facts(Duration.ZERO, true, false, 0))).isTrue();
  }

  @Test
  void aDateHoldsFromThatMoment() {
    UnlockCondition date = new UnlockCondition.At(ZonedDateTime.parse("2027-01-10T19:00+11:00"));
    Duration until = Duration.between(LAUNCH, Instant.parse("2027-01-10T08:00:00Z"));
    assertThat(date.met(facts(until.minusSeconds(1), false, false, 0))).isFalse();
    assertThat(date.met(facts(until, false, false, 0))).isTrue();
    assertThat(date.firstDate()).isPresent();
  }

  @Test
  void afterCountsFromLaunch() {
    UnlockCondition after = new UnlockCondition.After(Duration.ofDays(21));
    assertThat(after.met(facts(Duration.ofDays(20), false, false, 0))).isFalse();
    assertThat(after.met(facts(Duration.ofDays(21), false, false, 0))).isTrue();
    assertThat(
            after.met(
                new UnlockCondition.Facts(
                    LAUNCH.plus(Duration.ofDays(30)), Optional.empty(), false, false, 0)))
        .isFalse();
  }

  @Test
  void milestonesHoldOnceReached() {
    assertThat(new UnlockCondition.DragonKilled().met(facts(Duration.ZERO, false, true, 0)))
        .isTrue();
    UnlockCondition three = new UnlockCondition.PlayersReachedEnd(3);
    assertThat(three.met(facts(Duration.ZERO, false, false, 2))).isFalse();
    assertThat(three.met(facts(Duration.ZERO, false, false, 3))).isTrue();
  }

  @Test
  void anyAndAllCombine() {
    UnlockCondition dragon = new UnlockCondition.DragonKilled();
    UnlockCondition weeks = new UnlockCondition.After(Duration.ofDays(14));
    UnlockCondition any = new UnlockCondition.Any(List.of(dragon, weeks));
    UnlockCondition all = new UnlockCondition.All(List.of(dragon, weeks));
    assertThat(any.met(facts(Duration.ofDays(15), false, false, 0))).isTrue();
    assertThat(all.met(facts(Duration.ofDays(15), false, false, 0))).isFalse();
    assertThat(all.met(facts(Duration.ofDays(15), false, true, 0))).isTrue();
  }
}
