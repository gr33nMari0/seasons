// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.ScheduledTask;
import com.petepeterepeat.seasons.core.port.Scheduler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ScenarioSchedulerTest {
  private final ScenarioClock clock =
      new ScenarioClock(Clock.fixed(Instant.parse("2026-11-19T00:00:00Z"), ZoneOffset.UTC));

  /** The platform's timers, which fire only when a test runs one. */
  private final List<Runnable> timers = new ArrayList<>();

  private final Scheduler platform =
      new Scheduler() {
        @Override
        public void runGlobal(Runnable task) {
          task.run();
        }

        @Override
        public void runGlobalLater(Duration delay, Runnable task) {
          timers.add(task);
        }

        @Override
        public ScheduledTask runGlobalRepeating(Duration period, Runnable task) {
          timers.add(task);
          return () -> timers.remove(task);
        }

        @Override
        public void runForPlayer(PlayerId player, Runnable task) {}

        @Override
        public void runAt(Position position, Runnable task) {}

        @Override
        public void runAsync(Runnable task) {}
      };

  private final ScenarioScheduler scheduler = new ScenarioScheduler(platform, clock);

  @Test
  void advancingRunsDelayedTasksThatCameDueOnce() {
    AtomicInteger soon = new AtomicInteger();
    AtomicInteger later = new AtomicInteger();
    scheduler.runGlobalLater(Duration.ofSeconds(3), soon::incrementAndGet);
    scheduler.runGlobalLater(Duration.ofMinutes(5), later::incrementAndGet);

    clock.advance(Duration.ofSeconds(3));
    scheduler.catchUp();
    assertThat(soon.get()).isEqualTo(1);
    assertThat(later.get()).isEqualTo(0);

    timers.getFirst().run(); // the platform's own timer fires afterwards: nothing runs twice
    assertThat(soon.get()).isEqualTo(1);
    assertThat(scheduler.active()).isEqualTo(1);
  }

  @Test
  void advancingRunsEachRepeatingTaskOnceWhenItsPeriodPassed() {
    AtomicInteger sweeps = new AtomicInteger();
    ScheduledTask task =
        scheduler.runGlobalRepeating(Duration.ofSeconds(30), sweeps::incrementAndGet);

    clock.advance(Duration.ofSeconds(29));
    scheduler.catchUp();
    assertThat(sweeps.get()).isEqualTo(0);

    clock.advance(Duration.ofMinutes(10));
    scheduler.catchUp();
    scheduler.catchUp();
    assertThat(sweeps.get()).isEqualTo(1);

    task.cancel();
    clock.advance(Duration.ofMinutes(1));
    scheduler.catchUp();
    assertThat(sweeps.get()).isEqualTo(1);
    assertThat(scheduler.active()).isEqualTo(0);
  }

  @Test
  void forgottenDelayedTasksNeverRun() {
    AtomicInteger ran = new AtomicInteger();
    scheduler.runGlobalLater(Duration.ofSeconds(1), ran::incrementAndGet);
    scheduler.forgetDelayed();
    timers.getFirst().run();
    clock.advance(Duration.ofSeconds(5));
    scheduler.catchUp();
    assertThat(ran.get()).isEqualTo(0);
    assertThat(scheduler.active()).isEqualTo(0);
  }

  @Test
  void theClockOnlyMovesForwardAndResetsToRealTime() {
    Instant start = clock.instant();
    assertThat(clock.advance(Duration.ofMinutes(1))).isEqualTo(start.plusSeconds(60));
    assertThatThrownBy(() -> clock.advance(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    clock.reset();
    assertThat(clock.instant()).isEqualTo(start);
  }
}
