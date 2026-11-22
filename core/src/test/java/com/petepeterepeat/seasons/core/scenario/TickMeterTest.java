// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.scenario.TickMeter.Kind;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TickMeterTest {
  private final AtomicLong now = new AtomicLong();
  private final TickMeter meter = new TickMeter(now::get);

  /** Work that takes {@code nanos} by the test's clock. */
  private Runnable takes(long nanos) {
    return () -> now.addAndGet(nanos);
  }

  @Test
  void averagesSeasonsTimeOverEveryTickWatchedIdleOnesIncluded() {
    meter.tickStarted();
    meter.start();
    meter.measure(Kind.TASK, takes(3_000_000));
    meter.tickEnded();
    meter.tickStarted();
    meter.measure(Kind.LISTENER, takes(1_000_000));
    meter.tickEnded();
    meter.tickStarted();
    meter.tickEnded();
    meter.tickStarted();
    meter.tickEnded();

    TickMeter.Report report = meter.stop();

    assertThat(report.ticks()).isEqualTo(4);
    assertThat(report.averageNanos()).isEqualTo(1_000_000);
    assertThat(report.maxNanos()).isEqualTo(3_000_000);
    assertThat(report.tasks()).isEqualTo(1);
    assertThat(report.listeners()).isEqualTo(1);
    assertThat(report.line())
        .isEqualTo(
            "Perf ticks=4 average-ns=1000000 max-ns=3000000 display-max-ns=0 display-ticks=0"
                + " tasks=1 listeners=1");
  }

  @Test
  void nestedWorkCountsOnceAndDisplaysAlsoCountOnTheirOwn() {
    meter.tickStarted();
    meter.start();
    meter.measure(
        Kind.TASK,
        () -> {
          now.addAndGet(1_000_000);
          meter.measure(Kind.DISPLAY, takes(2_000_000));
          meter.measure(Kind.DISPLAY, takes(500_000));
        });
    meter.tickEnded();

    TickMeter.Report report = meter.stop();

    assertThat(report.maxNanos()).isEqualTo(3_500_000);
    assertThat(report.displayMaxNanos()).isEqualTo(2_500_000);
    assertThat(report.displayTicks()).isEqualTo(1);
    assertThat(report.tasks()).isEqualTo(1);
  }

  @Test
  void measuresNothingWhileStoppedOrOffTheServerThread() throws InterruptedException {
    meter.tickStarted();
    meter.measure(Kind.TASK, takes(9_000_000));
    meter.start();
    Thread other = new Thread(() -> meter.measure(Kind.TASK, takes(9_000_000)));
    other.start();
    other.join();
    meter.tickEnded();

    TickMeter.Report report = meter.stop();

    assertThat(report.ticks()).isEqualTo(1);
    assertThat(report.maxNanos()).isZero();
    assertThat(report.tasks()).isZero();
  }

  @Test
  void workThatThrowsIsStillTimed() {
    meter.tickStarted();
    meter.start();
    try {
      meter.measure(
          Kind.LISTENER,
          () -> {
            now.addAndGet(2_000_000);
            throw new IllegalStateException("boom");
          });
    } catch (IllegalStateException expected) {
      // The listener's own failure goes on to its caller.
    }
    meter.tickEnded();

    assertThat(meter.stop().maxNanos()).isEqualTo(2_000_000);
  }

  @Test
  void startingAgainForgetsTheLastRun() {
    meter.tickStarted();
    meter.start();
    meter.measure(Kind.TASK, takes(4_000_000));
    meter.tickEnded();
    meter.stop();
    meter.tickStarted();
    meter.start();
    meter.tickEnded();

    TickMeter.Report report = meter.stop();

    assertThat(report.ticks()).isEqualTo(1);
    assertThat(report.maxNanos()).isZero();
  }
}
