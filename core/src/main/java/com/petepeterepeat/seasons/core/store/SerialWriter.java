// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * The one thread that does Seasons' file I/O, so writes (and the journal in particular) happen in
 * the order they were asked for, and the server thread never waits on disk.
 */
public final class SerialWriter implements AutoCloseable {
  /** Work that touches files. */
  @FunctionalInterface
  public interface IoWork<T> {
    T run() throws IOException;
  }

  private final Executor executor;
  private final @Nullable ExecutorService owned;

  /** A writer with its own thread. */
  public SerialWriter() {
    ExecutorService thread =
        Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("seasons-writer").daemon(true).factory());
    this.owned = thread;
    this.executor = thread;
  }

  /** A writer on a given executor, which must run tasks one at a time in order (tests). */
  public SerialWriter(Executor executor) {
    this.owned = null;
    this.executor = executor;
  }

  /** Runs file work on the writer thread. The future fails with the work's exception. */
  public <T> CompletableFuture<T> submit(IoWork<T> work) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return work.run();
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        },
        executor);
  }

  /** Finishes queued writes, waiting up to 10 seconds. */
  @Override
  public void close() {
    ExecutorService thread = owned;
    if (thread == null) {
      return;
    }
    thread.shutdown();
    try {
      thread.awaitTermination(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
