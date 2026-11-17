// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.audit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

import com.google.gson.JsonObject;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code audit/YYYY-MM.jsonl}: one JSON object per line, appended on the writer thread. Secrets are
 * never written here.
 */
public final class AuditLog {
  private static final Logger LOG = LoggerFactory.getLogger(AuditLog.class);
  private static final DateTimeFormatter MONTH =
      DateTimeFormatter.ofPattern("uuuu-MM").withZone(ZoneOffset.UTC);

  /** Who did it, when Seasons did it itself. */
  public static final String SEASONS = "Seasons";

  private final Path directory;
  private final SerialWriter writer;
  private final Clock clock;

  /**
   * @param directory {@code <world>/seasons/audit}
   */
  public AuditLog(Path directory, SerialWriter writer, Clock clock) {
    this.directory = directory;
    this.writer = writer;
    this.clock = clock;
  }

  /**
   * Appends an entry.
   *
   * @param actor who did it: a player name, the console, or {@link #SEASONS}
   * @param action such as {@code archive}, {@code unarchive} or {@code tripwire}
   * @param target what it was done to, usually a player name
   * @param result such as {@code ok} or {@code failed}
   */
  public void record(String actor, String action, String target, String result, String details) {
    var _ = append(actor, action, target, result, details);
  }

  /** As {@link #record}, completing once the entry is on disk. Never completes exceptionally. */
  public CompletableFuture<Void> append(
      String actor, String action, String target, String result, String details) {
    Instant now = clock.instant();
    JsonObject entry = new JsonObject();
    entry.addProperty("time", now.toString());
    entry.addProperty("actor", actor);
    entry.addProperty("action", action);
    entry.addProperty("target", target);
    entry.addProperty("result", result);
    entry.addProperty("details", details);
    String line = entry + "\n";
    Path file = directory.resolve(MONTH.format(now) + ".jsonl");
    return writer
        .submit(() -> {
          Files.createDirectories(directory);
          try (FileChannel channel = FileChannel.open(file, CREATE, WRITE, APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(line.getBytes(UTF_8));
            while (buffer.hasRemaining()) {
              channel.write(buffer);
            }
            channel.force(true);
          }
          return Boolean.TRUE;
        })
        .thenApply(done -> (Void) null)
        .exceptionally(e -> {
          LOG.error("Can't write the audit log: {} {} {}", action, target, details, e);
          return null;
        });
  }
}
