// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

/**
 * Seasons Web's one writer thread, so its files are written in order and never on the server
 * thread: whole files atomically (temp file, fsync, rename) and audit lines appended.
 */
final class FileWriter implements AutoCloseable {
  private final ExecutorService thread =
      Executors.newSingleThreadExecutor(
          Thread.ofPlatform().name("SeasonsWeb-writer").daemon().factory());
  private final Logger log;

  FileWriter(Logger log) {
    this.log = log;
  }

  void replace(Path file, String text) {
    submit(() -> {
      Files.createDirectories(file.getParent());
      Path temp = file.resolveSibling(file.getFileName() + ".tmp");
      try (FileChannel out =
          FileChannel.open(
              temp,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE)) {
        out.write(java.nio.ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
        out.force(true);
      }
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    });
  }

  void append(Path file, String line) {
    submit(() -> {
      Files.createDirectories(file.getParent());
      Files.writeString(
          file,
          line + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    });
  }

  private interface Io {
    void run() throws IOException;
  }

  private void submit(Io work) {
    try {
      thread.execute(() -> {
        try {
          work.run();
        } catch (IOException | RuntimeException e) {
          log.error("Seasons Web can't write its files", e);
        }
      });
    } catch (java.util.concurrent.RejectedExecutionException e) {
      log.warn("Seasons Web is stopping; a write was dropped");
    }
  }

  /** Waits for every queued write. */
  void flush() {
    try {
      thread.submit(() -> {}).get(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public void close() {
    thread.shutdown();
    try {
      if (!thread.awaitTermination(10, TimeUnit.SECONDS)) {
        log.warn("Seasons Web's writer didn't finish in time");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
