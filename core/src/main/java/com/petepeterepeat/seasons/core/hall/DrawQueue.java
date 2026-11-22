// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Cells waiting to be drawn, worked through on the server thread a few a tick, so a rollover that
 * archives everyone at once causes no lag spike: each tick draws until its budget is spent, and at
 * least one cell, then leaves the rest to the next tick. Used only on the server thread.
 */
final class DrawQueue {
  private final Consumer<Runnable> nextTick;
  private final LongSupplier nanoTime;
  private final long budgetNanos;
  private final ArrayDeque<Waiting> waiting = new ArrayDeque<>();
  private boolean scheduled;

  private record Waiting(Runnable draw, CompletableFuture<Void> done) {}

  /**
   * @param nextTick runs a task on the server thread on a later tick
   * @param nanoTime a monotonic clock in nanoseconds
   * @param budgetNanos the drawing a tick may take; one cell is drawn whatever it takes
   */
  DrawQueue(Consumer<Runnable> nextTick, LongSupplier nanoTime, long budgetNanos) {
    this.nextTick = nextTick;
    this.nanoTime = nanoTime;
    this.budgetNanos = budgetNanos;
  }

  /** Queues a cell's drawing; completes once it is drawn, or with its failure. */
  CompletableFuture<Void> submit(Runnable draw) {
    CompletableFuture<Void> done = new CompletableFuture<>();
    waiting.add(new Waiting(draw, done));
    schedule();
    return done;
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
    do {
      Waiting next = waiting.poll();
      if (next == null) {
        return;
      }
      try {
        next.draw().run();
        next.done().complete(null);
      } catch (RuntimeException e) {
        next.done().completeExceptionally(e);
      }
    } while (nanoTime.getAsLong() - started < budgetNanos);
    if (!waiting.isEmpty()) {
      schedule();
    }
  }
}
