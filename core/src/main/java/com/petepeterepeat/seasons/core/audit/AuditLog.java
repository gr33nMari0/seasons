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
import java.io.IOException;
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

  /**
   * Where a page of {@link #page} ended: entries older than {@code time}, and of those at exactly
   * {@code time}, all but the last {@code shown}, which that page already showed. Written as {@code
   * <time>~<shown>}.
   */
  public record Cursor(Instant time, int shown) {
    @Override
    public String toString() {
      return time + "~" + shown;
    }

    /** A cursor as {@link #toString} wrote it, or empty if it isn't one. */
    public static Optional<Cursor> parse(String text) {
      int cut = text.lastIndexOf('~');
      if (cut <= 0) {
        return Optional.empty();
      }
      try {
        int shown = Integer.parseInt(text.substring(cut + 1));
        return shown < 0
            ? Optional.empty()
            : Optional.of(new Cursor(Instant.parse(text.substring(0, cut)), shown));
      } catch (NumberFormatException | java.time.format.DateTimeParseException e) {
        return Optional.empty();
      }
    }
  }

  /** A page of entries, oldest first, and where the next, older, page starts if there is one. */
  public record Page(List<Entry> entries, Optional<Cursor> next) {}

  /**
   * The newest {@code count} entries older than {@code before}, of the action {@code kind} (or one
   * starting with it) and the {@code player} as actor or target, all ignoring case. Read from the
   * cursor's month and the one before it, or this month's and last month's: a page never reaches
   * back further than two months from where it starts, and the next page carries on from there.
   */
  public CompletableFuture<Page> page(
      Optional<Cursor> before, Optional<String> kind, Optional<String> player, int count) {
    Instant from = before.map(Cursor::time).orElseGet(clock::instant);
    Instant monthBefore = from.atZone(ZoneOffset.UTC).minusMonths(1).toInstant();
    List<Path> files =
        List.of(
            directory.resolve(MONTH.format(monthBefore) + ".jsonl"),
            directory.resolve(MONTH.format(from) + ".jsonl"));
    String wanted = kind.map(k -> k.strip().toLowerCase(java.util.Locale.ROOT)).orElse("");
    return writer.submit(() -> {
      List<Entry> found = new ArrayList<>();
      for (Path file : files) {
        if (!Files.exists(file)) {
          continue;
        }
        for (String line : Files.readAllLines(file, UTF_8)) {
          entry(line)
              .filter(e -> e.action().toLowerCase(java.util.Locale.ROOT).startsWith(wanted))
              .filter(e ->
                  player.isEmpty()
                      || e.actor().equalsIgnoreCase(player.get())
                      || e.target().equalsIgnoreCase(player.get()))
              .ifPresent(found::add);
        }
      }
      if (before.isPresent()) {
        Cursor c = before.get();
        long at = found.stream().filter(e -> e.time().equals(c.time())).count();
        long keepAt = Math.max(0, at - c.shown());
        List<Entry> older = new ArrayList<>();
        long seenAt = 0;
        for (Entry e : found) {
          if (e.time().isBefore(c.time())) {
            older.add(e);
          } else if (e.time().equals(c.time()) && seenAt++ < keepAt) {
            older.add(e);
          }
        }
        found = older;
      }
      int start = Math.max(0, found.size() - count);
      List<Entry> page = List.copyOf(found.subList(start, found.size()));
      Optional<Cursor> next = Optional.empty();
      boolean olderMonths = Files.exists(directory) && hasOlderFiles(MONTH.format(monthBefore));
      if (!page.isEmpty() && (start > 0 || olderMonths)) {
        Instant oldest = page.getFirst().time();
        int shownAt = (int) page.stream().filter(e -> e.time().equals(oldest)).count();
        // Entries at that instant that an earlier page showed are still left out.
        int already = before.filter(c -> c.time().equals(oldest)).map(Cursor::shown).orElse(0);
        next = Optional.of(new Cursor(oldest, shownAt + already));
      } else if (page.isEmpty() && olderMonths) {
        next =
            Optional.of(
                new Cursor(
                    monthBefore
                        .atZone(ZoneOffset.UTC)
                        .withDayOfMonth(1)
                        .toLocalDate()
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant(),
                    0));
      }
      return new Page(page, next);
    });
  }

  /** Whether a month file older than {@code month} exists. */
  private boolean hasOlderFiles(String month) throws IOException {
    try (java.util.stream.Stream<Path> files = Files.list(directory)) {
      return files
          .map(f -> f.getFileName().toString())
          .filter(n -> n.endsWith(".jsonl"))
          .anyMatch(n -> n.substring(0, n.length() - ".jsonl".length()).compareTo(month) < 0);
    }
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
