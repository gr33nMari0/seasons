// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.store;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Every Seasons write: a temp file, fsync, then an atomic rename over the target, so a crash leaves
 * either the old file or the new one, never half of one.
 */
public final class AtomicFiles {
  /** How many earlier copies of a human-editable file are kept. */
  public static final int BACKUPS_KEPT = 5;

  private static final DateTimeFormatter BACKUP_STAMP =
      DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss.SSSSSSSSS'Z'").withZone(ZoneOffset.UTC);

  private AtomicFiles() {}

  /** Replaces {@code file} with {@code data}, creating parent directories as needed. */
  public static void write(Path file, byte[] data) throws IOException {
    Path target = file.toAbsolutePath();
    Path dir = Objects.requireNonNull(target.getParent(), "a file has a parent directory");
    Files.createDirectories(dir);
    Path temp = Files.createTempFile(dir, "." + target.getFileName(), ".tmp");
    try {
      try (FileChannel channel = FileChannel.open(temp, WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }
      Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temp);
    }
    syncDirectory(dir);
  }

  /**
   * Like {@link #write}, first copying the current file into {@code backupDir} and keeping only the
   * newest {@link #BACKUPS_KEPT} copies there.
   */
  public static void writeKeepingBackups(Path file, byte[] data, Path backupDir)
      throws IOException {
    String name = file.getFileName().toString();
    if (Files.exists(file)) {
      Files.createDirectories(backupDir);
      Path backup = backupDir.resolve(name + "." + BACKUP_STAMP.format(Instant.now()));
      Files.copy(file, backup, REPLACE_EXISTING);
      List<Path> copies;
      try (Stream<Path> listed = Files.list(backupDir)) {
        copies =
            listed.filter(p -> p.getFileName().toString().startsWith(name + ".")).sorted().toList();
      }
      for (int i = 0; i < copies.size() - BACKUPS_KEPT; i++) {
        Files.deleteIfExists(copies.get(i));
      }
    }
    write(file, data);
  }

  /** Makes the rename itself durable. Some file systems can't open a directory; that's fine. */
  private static void syncDirectory(Path dir) {
    try (FileChannel channel = FileChannel.open(dir, READ)) {
      channel.force(true);
    } catch (IOException e) {
      // Not supported here (Windows, for one); the rename is still atomic.
    }
  }
}
