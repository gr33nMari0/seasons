// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.scenario;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * How much time Seasons adds to each server tick, for the performance scenario A43 (docs/adr/0003).
 * It times work on the server thread only: every task Seasons schedules, every event listener and
 * every display drawn or removed. Work inside other measured work counts once; display work also
 * counts on its own, for A43's limit on drawing displays. Ticks are bounded by {@link #tickStarted}
 * and {@link #tickEnded}, which the test plugin calls from Paper's tick events. It measures nothing
 * until {@link #start}, and exists only on a scenario server.
 */
public final class TickMeter {
  /** What a piece of measured work is. */
  public enum Kind {
    TASK,
    LISTENER,
    DISPLAY
  }

  /** How many of the slowest pieces of work a report names. */
  private static final int SLOWEST = 10;

  private final LongSupplier nanoTime;
  private volatile @Nullable Thread serverThread;
  private volatile boolean running;

  // Only the server thread touches what follows.
  private int depth;
  private int displayDepth;
  private long tickNanos;
  private long tickDisplayNanos;
  private long ticks;
  private long totalNanos;
  private long maxNanos;
  private long displayMaxNanos;
  private long displayTicks;
  private long tasks;
  private long listeners;
  private final Map<String, Long> slowest = new HashMap<>();

  /**
   * @param nanoTime a monotonic clock in nanoseconds
   */
  public TickMeter(LongSupplier nanoTime) {
    this.nanoTime = nanoTime;
  }

  /** A tick begins; the thread calling this is the server thread. */
  public void tickStarted() {
    serverThread = Thread.currentThread();
  }

  /** A tick has ended: what Seasons took in it joins the totals. */
  public void tickEnded() {
    if (!running || Thread.currentThread() != serverThread) {
      return;
    }
    ticks++;
    totalNanos += tickNanos;
    maxNanos = Math.max(maxNanos, tickNanos);
    if (tickDisplayNanos > 0) {
      displayTicks++;
      displayMaxNanos = Math.max(displayMaxNanos, tickDisplayNanos);
    }
    tickNanos = 0;
    tickDisplayNanos = 0;
  }

  /** Forgets any earlier run and starts measuring. Called on the server thread. */
  public void start() {
    ticks = 0;
    totalNanos = 0;
    maxNanos = 0;
    displayMaxNanos = 0;
    displayTicks = 0;
    tasks = 0;
    listeners = 0;
    tickNanos = 0;
    tickDisplayNanos = 0;
    slowest.clear();
    running = true;
  }

  /** Stops measuring and reports the ticks watched. Called on the server thread. */
  public Report stop() {
    running = false;
    return new Report(
        ticks,
        ticks == 0 ? 0 : totalNanos / ticks,
        maxNanos,
        displayMaxNanos,
        displayTicks,
        tasks,
        listeners,
        slowest.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(SLOWEST)
            .map(e -> e.getKey() + "=" + e.getValue())
            .toList());
  }

  /** Runs {@code work}, timing it if the meter is running and this is the server thread. */
  public void measure(Kind kind, Runnable work) {
    measure(kind, kind.name(), work);
  }

  /**
   * As {@link #measure(Kind, Runnable)}, naming the work, such as a task's or listener's class, for
   * the report's list of the slowest work.
   */
  public void measure(Kind kind, String label, Runnable work) {
    var _ =
        measured(
            kind,
            label,
            () -> {
              work.run();
              return Boolean.TRUE;
            });
  }

  /** As {@link #measure(Kind, Runnable)}, for work with a result. */
  public <T> T measured(Kind kind, Supplier<T> work) {
    return measured(kind, kind.name(), work);
  }

  /** As {@link #measure(Kind, String, Runnable)}, for work with a result. */
  public <T> T measured(Kind kind, String label, Supplier<T> work) {
    if (!running || Thread.currentThread() != serverThread) {
      return work.get();
    }
    if (depth == 0) {
      if (kind == Kind.TASK) {
        tasks++;
      } else if (kind == Kind.LISTENER) {
        listeners++;
      }
    }
    depth++;
    if (kind == Kind.DISPLAY) {
      displayDepth++;
    }
    long started = nanoTime.getAsLong();
    try {
      return work.get();
    } finally {
      long took = nanoTime.getAsLong() - started;
      depth--;
      if (depth == 0) {
        tickNanos += took;
        slowest.merge(kind + " " + label, took, Math::max);
      }
      if (kind == Kind.DISPLAY) {
        displayDepth--;
        if (displayDepth == 0) {
          tickDisplayNanos += took;
          if (depth > 0) {
            slowest.merge(kind + " " + label, took, Math::max);
          }
        }
      }
    }
  }

  /**
   * What one run measured, in nanoseconds.
   *
   * @param slowest the work that took longest at once, slowest first, each as its kind, its label
   *     and its longest time, such as {@code TASK com.example.Sweep=1200000}
   */
  public record Report(
      long ticks,
      long averageNanos,
      long maxNanos,
      long displayMaxNanos,
      long displayTicks,
      long tasks,
      long listeners,
      List<String> slowest) {
    public Report {
      slowest = List.copyOf(slowest);
    }

    /** The line {@code /scenario perf stop} prints. */
    public String line() {
      return "Perf ticks=%d average-ns=%d max-ns=%d display-max-ns=%d display-ticks=%d tasks=%d listeners=%d"
          .formatted(
              ticks, averageNanos, maxNanos, displayMaxNanos, displayTicks, tasks, listeners);
    }
  }
}
