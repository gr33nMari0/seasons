// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.ScheduledTask;
import com.petepeterepeat.seasons.core.port.Scheduler;
import com.petepeterepeat.seasons.core.scenario.ScenarioClock;
import com.petepeterepeat.seasons.core.scenario.ScenarioHooks;
import com.petepeterepeat.seasons.core.scenario.ScenarioScheduler;
import java.time.Clock;
import java.time.Duration;

/** Scenario hooks for core tests that only use crash points and reads. */
final class TestHooks {
  private TestHooks() {}

  static ScenarioHooks of(Runnable halt) {
    Scheduler idle =
        new Scheduler() {
          @Override
          public void runGlobal(Runnable task) {}

          @Override
          public void runGlobalLater(Duration delay, Runnable task) {}

          @Override
          public ScheduledTask runGlobalRepeating(Duration period, Runnable task) {
            return () -> {};
          }

          @Override
          public void runForPlayer(PlayerId player, Runnable task) {}

          @Override
          public void runAt(Position position, Runnable task) {}

          @Override
          public void runAsync(Runnable task) {}
        };
    ScenarioClock clock = new ScenarioClock(Clock.systemUTC());
    return new ScenarioHooks(halt, clock, new ScenarioScheduler(idle, clock));
  }
}
