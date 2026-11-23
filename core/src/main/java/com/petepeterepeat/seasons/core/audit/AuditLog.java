// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.audit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.petepeterepeat.seasons.core.log.SeasonsLog;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;

/**
 * {@code audit/YYYY-MM.jsonl}: one JSON object per line, appended on the writer thread. Secrets are
 * never written here.
 */
public final class AuditLog {
  private static final Logger LOG = SeasonsLog.get();
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

  /** One entry as it was written. */
  public record Entry(
      Instant time, String actor, String action, String target, String result, String details) {}

  /**
   * The last {@code count} entries of this month's and last month's files, oldest first, read on
   * the writer thread after every entry already appended; with a player, only the entries whose
   * actor or target is that name or UUID, ignoring case. A line that can't be read is skipped.
   */
  public CompletableFuture<List<Entry>> recent(Optional<String> player, int count) {
    Instant now = clock.instant();
    Instant lastMonth = now.atZone(ZoneOffset.UTC).minusMonths(1).toInstant();
    List<Path> files =
        List.of(
            directory.resolve(MONTH.format(lastMonth) + ".jsonl"),
            directory.resolve(MONTH.format(now) + ".jsonl"));
    return writer.submit(() -> {
      List<Entry> found = new ArrayList<>();
      for (Path file : files) {
        if (!Files.exists(file)) {
          continue;
        }
        for (String line : Files.readAllLines(file, UTF_8)) {
          entry(line)
              .filter(e ->
                  player.isEmpty()
                      || e.actor().equalsIgnoreCase(player.get())
                      || e.target().equalsIgnoreCase(player.get()))
              .ifPresent(found::add);
        }
      }
      return List.copyOf(found.subList(Math.max(0, found.size() - count), found.size()));
    });
  }

  private static final List<String> FIELDS =
      List.of("time", "actor", "action", "target", "result", "details");

  private static Optional<Entry> entry(String line) {
    try {
      if (!(JsonParser.parseString(line) instanceof JsonObject json)
          || !FIELDS.stream().allMatch(field -> json.get(field) instanceof JsonPrimitive)) {
        return Optional.empty();
      }
      return Optional.of(
          new Entry(
              Instant.parse(json.get("time").getAsString()),
              json.get("actor").getAsString(),
              json.get("action").getAsString(),
              json.get("target").getAsString(),
              json.get("result").getAsString(),
              json.get("details").getAsString()));
    } catch (JsonParseException | java.time.format.DateTimeParseException e) {
      // A line cut short by a crash, or written by hand.
      return Optional.empty();
    }
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
