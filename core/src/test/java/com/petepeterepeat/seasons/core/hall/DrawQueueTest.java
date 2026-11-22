// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DrawQueueTest {
  private final AtomicLong now = new AtomicLong();

  /** Work handed to the next tick, run when the test ticks. */
  private final Queue<Runnable> nextTick = new ArrayDeque<>();

  private final DrawQueue queue = new DrawQueue(nextTick::add, now::get, 2_000_000);
  private final List<Integer> drawn = new ArrayList<>();

  private Runnable draw(int cell) {
    return () -> {
      now.addAndGet(1_000_000);
      drawn.add(cell);
    };
  }

  private void tick() {
    List<Runnable> due = new ArrayList<>(nextTick);
    nextTick.clear();
    due.forEach(Runnable::run);
  }

  @Test
  void drawsAFewCellsEachTickWithinItsBudget() {
    List<CompletableFuture<Void>> done = new ArrayList<>();
    for (int cell = 1; cell <= 5; cell++) {
      done.add(queue.submit(draw(cell)));
    }
    assertThat(drawn).isEmpty();

    tick();
    assertThat(drawn).containsExactly(1, 2);
    assertThat(done.get(1)).isDone();
    assertThat(done.get(2)).isNotDone();

    tick();
    assertThat(drawn).containsExactly(1, 2, 3, 4);

    tick();
    assertThat(drawn).containsExactly(1, 2, 3, 4, 5);
    assertThat(done).allMatch(CompletableFuture::isDone);
    assertThat(nextTick).isEmpty();
  }

  @Test
  void aSlowDrawStillLetsOneThroughEachTick() {
    var _ = queue.submit(() -> now.addAndGet(9_000_000));
    var _ = queue.submit(draw(2));

    tick();
    assertThat(drawn).isEmpty();
    tick();
    assertThat(drawn).containsExactly(2);
  }

  @Test
  void aFailedDrawFailsOnlyItsOwnFuture() {
    CompletableFuture<Void> failed =
        queue.submit(() -> {
          throw new IllegalStateException("no such cell");
        });
    CompletableFuture<Void> next = queue.submit(draw(2));

    tick();

    assertThat(failed).isCompletedExceptionally();
    assertThat(next).isCompleted();
    assertThat(drawn).containsExactly(2);
  }

  @Test
  void schedulesOneDrainHoweverManyAreQueued() {
    var _ = queue.submit(draw(1));
    var _ = queue.submit(draw(2));
    assertThat(nextTick).hasSize(1);
    tick();
    var _ = queue.submit(draw(3));
    assertThat(nextTick).hasSize(1);
  }

  @Test
  void aCellIsDrawnADisplayAtATimeAndFinishedBeforeTheNextStarts() {
    CompletableFuture<Void> first =
        queue.submitSteps(() -> {
          drawn.add(10);
          return List.of(draw(11), draw(12), draw(13));
        });
    CompletableFuture<Void> second = queue.submit(draw(2));

    tick();
    assertThat(drawn).containsExactly(10, 11, 12);
    assertThat(first).isNotDone();

    tick();
    assertThat(drawn).containsExactly(10, 11, 12, 13, 2);
    assertThat(first).isCompleted();
    assertThat(second).isCompleted();
  }

  @Test
  void stopsBeforeAStepThatWouldRunPastTheBudget() {
    var _ = queue.submit(() -> now.addAndGet(1_500_000));
    var _ = queue.submit(draw(2));

    tick();
    assertThat(drawn).isEmpty();
    tick();
    assertThat(drawn).containsExactly(2);
  }

  @Test
  void aStoppedQueueDrawsNothingMore() {
    var _ = queue.submit(draw(1));
    var _ = queue.submit(draw(2));
    var _ = queue.submit(draw(3));
    tick();
    assertThat(drawn).containsExactly(1, 2);

    queue.stop();
    tick();
    var _ = queue.submit(draw(4));
    tick();

    assertThat(drawn).containsExactly(1, 2);
    assertThat(nextTick).isEmpty();
  }
}
