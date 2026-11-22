// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.scenario;

import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.ScheduledTask;
import com.petepeterepeat.seasons.core.port.Scheduler;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Seasons' scheduler on a scenario server: the platform's own, plus a note of every delayed and
 * repeating global task, so that moving the {@link ScenarioClock} forward can run at once what
 * would have come due in the time skipped (docs/adr/0003). Every task that runs on the server
 * thread is timed by the {@link TickMeter} (A43). Every other server uses the platform's scheduler
 * directly.
 */
public final class ScenarioScheduler implements Scheduler {
  private final Scheduler platform;
  private final Clock clock;
  private final TickMeter meter;
  private final Set<Delayed> delayed = ConcurrentHashMap.newKeySet();
  private final Set<Repeating> repeating = ConcurrentHashMap.newKeySet();

  public ScenarioScheduler(Scheduler platform, Clock clock) {
    this(platform, clock, new TickMeter(System::nanoTime));
  }

  public ScenarioScheduler(Scheduler platform, Clock clock, TickMeter meter) {
    this.platform = platform;
    this.clock = clock;
    this.meter = meter;
  }

  /** The meter that times the tasks this scheduler runs. */
  public TickMeter meter() {
    return meter;
  }

  private Runnable timed(Runnable task) {
    String label = task.getClass().getName();
    return () -> meter.measure(TickMeter.Kind.TASK, label, task);
  }

  /**
   * Runs, on the calling thread, every delayed task now due by the clock, then every repeating task
   * whose period has passed since it last ran (once each, however many periods passed). The caller
   * is on the thread that owns global state.
   */
  public void catchUp() {
    long now = clock.millis();
    delayed.stream()
        .filter(d -> d.due <= now)
        .sorted((a, b) -> Long.compare(a.due, b.due))
        .forEach(Delayed::run);
    repeating.stream().filter(r -> now - r.lastRun >= r.period).toList().forEach(Repeating::run);
  }

  /** Delayed tasks not yet run and repeating tasks not cancelled. */
  public int active() {
    return delayed.size() + repeating.size();
  }

  /** Drops delayed tasks not yet run, so a stopped Seasons gets none of them. */
  public void forgetDelayed() {
    delayed.forEach(d -> d.ran.set(true));
    delayed.clear();
  }

  @Override
  public void runGlobal(Runnable task) {
    platform.runGlobal(timed(task));
  }

  @Override
  public void runGlobalLater(Duration delay, Runnable task) {
    Delayed later = new Delayed(clock.millis() + delay.toMillis(), timed(task));
    delayed.add(later);
    platform.runGlobalLater(delay, later::run);
  }

  @Override
  public ScheduledTask runGlobalRepeating(Duration period, Runnable task) {
    Repeating every = new Repeating(period.toMillis(), timed(task), clock.millis());
    repeating.add(every);
    ScheduledTask scheduled = platform.runGlobalRepeating(period, every::run);
    return () -> {
      repeating.remove(every);
      scheduled.cancel();
    };
  }

  @Override
  public void runForPlayer(PlayerId player, Runnable task) {
    platform.runForPlayer(player, timed(task));
  }

  @Override
  public void runAt(Position position, Runnable task) {
    platform.runAt(position, timed(task));
  }

  @Override
  public void runAsync(Runnable task) {
    platform.runAsync(task);
  }

  private final class Delayed {
    private final long due;
    private final Runnable task;
    private final AtomicBoolean ran = new AtomicBoolean();

    private Delayed(long due, Runnable task) {
      this.due = due;
      this.task = task;
    }

    /** Runs once, whichever comes first: the platform's timer or a catch-up. */
    private void run() {
      if (ran.compareAndSet(false, true)) {
        delayed.remove(this);
        task.run();
      }
    }
  }

  private final class Repeating {
    private final long period;
    private final Runnable task;
    private volatile long lastRun;

    private Repeating(long period, Runnable task, long scheduledAt) {
      this.period = period;
      this.task = task;
      this.lastRun = scheduledAt;
    }

    private void run() {
      if (!repeating.contains(this)) {
        return;
      }
      lastRun = clock.millis();
      task.run();
    }
  }
}
