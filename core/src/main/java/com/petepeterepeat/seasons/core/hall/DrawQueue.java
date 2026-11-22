// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Cells waiting to be drawn, worked through on the server thread a few displays a tick, so a
 * rollover that archives everyone at once causes no lag spike (A43). A cell is drawn in steps, such
 * as clearing it and then each display, and its steps run in order, possibly over several ticks,
 * before the next cell's first: two draws of one cell never interleave. Each tick runs at least one
 * step, then another only while the last one's time would still fit in the budget. Used only on the
 * server thread.
 */
final class DrawQueue {
  private final Consumer<Runnable> nextTick;
  private final LongSupplier nanoTime;
  private final long budgetNanos;
  private final ArrayDeque<Job> waiting = new ArrayDeque<>();
  private boolean scheduled;

  /** One cell's drawing: its first step, which gives the rest, and those still to run. */
  private static final class Job {
    private final Supplier<List<Runnable>> first;
    private final CompletableFuture<Void> done = new CompletableFuture<>();
    private final ArrayDeque<Runnable> steps = new ArrayDeque<>();
    private boolean started;

    private Job(Supplier<List<Runnable>> first) {
      this.first = first;
    }
  }

  /**
   * @param nextTick runs a task on the server thread on a later tick
   * @param nanoTime a monotonic clock in nanoseconds
   * @param budgetNanos the drawing a tick may take; one step runs whatever it takes
   */
  DrawQueue(Consumer<Runnable> nextTick, LongSupplier nanoTime, long budgetNanos) {
    this.nextTick = nextTick;
    this.nanoTime = nanoTime;
    this.budgetNanos = budgetNanos;
  }

  /** Queues drawing done in one step; completes once it is done, or with its failure. */
  CompletableFuture<Void> submit(Runnable draw) {
    return submitSteps(() -> {
      draw.run();
      return List.of();
    });
  }

  /**
   * Queues drawing done in steps: {@code first} runs as the first step and gives the steps that
   * follow it. Completes once the last is done, or with the first failure, which ends the job.
   */
  CompletableFuture<Void> submitSteps(Supplier<List<Runnable>> first) {
    Job job = new Job(first);
    waiting.add(job);
    schedule();
    return job.done;
  }

  private void schedule() {
    if (!scheduled) {
      scheduled = true;
      nextTick.accept(this::drain);
    }
  }

  private void drain() {
    scheduled = false;
    long started = nanoTime.getAsLong();
    long last;
    do {
      Job job = waiting.peek();
      if (job == null) {
        return;
      }
      long stepStarted = nanoTime.getAsLong();
      step(job);
      last = nanoTime.getAsLong() - stepStarted;
    } while (nanoTime.getAsLong() - started + last <= budgetNanos);
    if (!waiting.isEmpty()) {
      schedule();
    }
  }

  /** Runs a job's next step, and finishes the job when it has none left or fails. */
  private void step(Job job) {
    try {
      if (!job.started) {
        job.started = true;
        job.steps.addAll(job.first.get());
      } else {
        job.steps.removeFirst().run();
      }
    } catch (RuntimeException e) {
      waiting.remove(job);
      job.done.completeExceptionally(e);
      return;
    }
    if (job.steps.isEmpty()) {
      waiting.remove(job);
      job.done.complete(null);
    }
  }
}
