// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.store;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

import com.petepeterepeat.seasons.core.port.PlayerId;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.zip.CRC32;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The append-only log written before any item moves. Each line is tab-separated: sequence, time,
 * kind, player (or {@code -}), season, payload hash, and a CRC-32 of the rest, so a line cut short
 * by a crash is recognised and dropped instead of misread.
 *
 * <p>Appends run on the {@link SerialWriter} and complete only once the line is fsynced, so the
 * caller makes the world change it covers in the future's continuation.
 */
public final class Journal {
  private static final Logger LOG = LoggerFactory.getLogger(Journal.class);
  private static final String NO_PLAYER = "-";

  private final Path file;
  private final SerialWriter writer;
  private final Clock clock;
  private final List<JournalEntry> entries;
  private final int damagedLines;
  private volatile long lastSequence;
  private volatile @Nullable String lastError;

  private Journal(
      Path file, SerialWriter writer, Clock clock, List<JournalEntry> entries, int damaged) {
    this.file = file;
    this.writer = writer;
    this.clock = clock;
    this.entries = new CopyOnWriteArrayList<>(entries);
    this.damagedLines = damaged;
    this.lastSequence = entries.isEmpty() ? 0 : entries.getLast().sequence();
  }

  /**
   * Reads the journal at startup. Damaged lines are dropped: the file is rewritten without them,
   * and the original is kept beside it as {@code journal.log.damaged-<time>} for inspection.
   */
  public static Journal open(Path file, SerialWriter writer, Clock clock) throws IOException {
    List<JournalEntry> entries = new ArrayList<>();
    int damaged = 0;
    Path parent = file.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    if (Files.exists(file)) {
      String content = Files.readString(file, UTF_8);
      String[] lines = content.split("\n", -1);
      for (int i = 0; i < lines.length; i++) {
        String line = lines[i];
        boolean last = i == lines.length - 1;
        if (line.isEmpty() && last) {
          break;
        }
        Optional<JournalEntry> entry = last ? Optional.empty() : parse(line);
        if (entry.isPresent()) {
          entries.add(entry.get());
        } else {
          damaged++;
          LOG.warn("Dropping damaged journal line {} of {}", i + 1, file);
        }
      }
      if (damaged > 0) {
        Files.copy(file, file.resolveSibling(file.getFileName() + ".damaged-" + clock.millis()));
        AtomicFiles.write(file, render(entries).getBytes(UTF_8));
      }
    }
    return new Journal(file, writer, clock, entries, damaged);
  }

  /** Every entry, oldest first: those read at startup and those appended since. */
  public List<JournalEntry> entries() {
    return List.copyOf(entries);
  }

  /** Appends an entry; the future completes once it is on disk. */
  public CompletableFuture<JournalEntry> append(
      JournalKind kind, @Nullable PlayerId player, int season, String payloadHash) {
    if (payloadHash.isEmpty() || payloadHash.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException("A payload hash is one non-empty word");
    }
    return writer.submit(() -> {
      JournalEntry entry =
          new JournalEntry(
              lastSequence + 1,
              clock.instant(),
              kind,
              Optional.ofNullable(player),
              season,
              payloadHash);
      try (FileChannel channel = FileChannel.open(file, CREATE, WRITE, APPEND)) {
        ByteBuffer buffer = ByteBuffer.wrap((line(entry) + "\n").getBytes(UTF_8));
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      } catch (IOException e) {
        lastError = e.toString();
        throw e;
      }
      lastError = null;
      lastSequence = entry.sequence();
      entries.add(entry);
      return entry;
    });
  }

  /**
   * Rewrites the journal with only the entries {@code keep} accepts, such as those of operations
   * still open after a completed rollover. Sequence numbers carry on from where they were.
   *
   * @return the number of entries kept
   */
  public CompletableFuture<Integer> compact(Predicate<JournalEntry> keep) {
    return writer.submit(() -> {
      List<JournalEntry> kept = entries.stream().filter(keep).toList();
      AtomicFiles.write(file, render(kept).getBytes(UTF_8));
      entries.clear();
      entries.addAll(kept);
      return kept.size();
    });
  }

  public JournalHealth health() {
    return new JournalHealth(
        entries.size(), lastSequence, damagedLines, Optional.ofNullable(lastError));
  }

  private static String render(List<JournalEntry> entries) {
    StringBuilder out = new StringBuilder();
    for (JournalEntry entry : entries) {
      out.append(line(entry)).append('\n');
    }
    return out.toString();
  }

  static String line(JournalEntry entry) {
    String body =
        String.join(
            "\t",
            Long.toString(entry.sequence()),
            entry.time().toString(),
            entry.kind().name(),
            entry.player().map(PlayerId::toString).orElse(NO_PLAYER),
            Integer.toString(entry.season()),
            entry.payloadHash());
    return body + "\t" + crc(body);
  }

  static Optional<JournalEntry> parse(String line) {
    int split = line.lastIndexOf('\t');
    if (split < 0 || !line.substring(split + 1).equals(crc(line.substring(0, split)))) {
      return Optional.empty();
    }
    String[] fields = line.substring(0, split).split("\t", -1);
    if (fields.length != 6) {
      return Optional.empty();
    }
    try {
      Optional<PlayerId> player =
          fields[3].equals(NO_PLAYER)
              ? Optional.empty()
              : Optional.of(new PlayerId(UUID.fromString(fields[3])));
      return Optional.of(
          new JournalEntry(
              Long.parseLong(fields[0]),
              Instant.parse(fields[1]),
              JournalKind.valueOf(fields[2]),
              player,
              Integer.parseInt(fields[4]),
              fields[5]));
    } catch (IllegalArgumentException | DateTimeParseException e) {
      return Optional.empty();
    }
  }

  private static String crc(String body) {
    CRC32 crc = new CRC32();
    crc.update(body.getBytes(UTF_8));
    return HexFormat.of().toHexDigits((int) crc.getValue());
  }
}
