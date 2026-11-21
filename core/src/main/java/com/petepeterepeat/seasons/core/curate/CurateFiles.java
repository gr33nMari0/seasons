// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.curate;

import com.petepeterepeat.seasons.core.archive.ArchiveSlot;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.store.AtomicFiles;
import com.petepeterepeat.seasons.core.store.nbt.Nbt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Where curated items wait: {@code data/curate/<id>.dat} holds an item from the moment its curation
 * is journalled until it is delivered, so a crash between taking it out of the archive and
 * delivering it can put it back; {@code data/recycle/<id>.dat} is the recycle bin, holding a
 * destroyed item for 30 days. Each file is vanilla NBT with the item's own bytes, exactly as the
 * archive held them, and who, where and why. Writes are atomic and fsynced. Writer thread only.
 */
public final class CurateFiles {
  /**
   * What a curation's id looks like, so one typed into {@code /hof curate undo} is never a path.
   */
  public static final Pattern ID = Pattern.compile("[a-z0-9-]{1,40}");

  private final Path pending;
  private final Path recycle;

  /**
   * One item kept aside.
   *
   * @param name the owner's name at archive time
   * @param slot where it sat in the archive
   * @param curator who moved it
   * @param at when
   */
  public record Kept(
      String id,
      PlayerId owner,
      String name,
      int season,
      ArchiveSlot slot,
      String curator,
      String reason,
      Instant at,
      NeutralItem item) {}

  /**
   * @param data {@code <world>/seasons/data}
   */
  public CurateFiles(Path data) {
    this.pending = data.resolve("curate");
    this.recycle = data.resolve("recycle");
  }

  /** Keeps an item aside while its curation runs. */
  public void keep(Kept kept) throws IOException {
    write(pending, kept);
  }

  /** The item a curation kept aside, if it is still there. */
  public Optional<Kept> kept(String id) throws IOException {
    return read(pending, id);
  }

  /** Forgets an item kept aside once its curation has settled. */
  public void release(String id) throws IOException {
    if (ID.matcher(id).matches()) {
      Files.deleteIfExists(pending.resolve(id + ".dat"));
    }
  }

  /** Puts a destroyed item in the recycle bin. */
  public void recycle(Kept kept) throws IOException {
    write(recycle, kept);
  }

  /** A destroyed item in the recycle bin, if it is there. */
  public Optional<Kept> recycled(String id) throws IOException {
    return read(recycle, id);
  }

  /** Takes an item out of the recycle bin for good, as an undo or the 30 days running out do. */
  public void unrecycle(String id) throws IOException {
    if (ID.matcher(id).matches()) {
      Files.deleteIfExists(recycle.resolve(id + ".dat"));
    }
  }

  /**
   * Empties the recycle bin of everything put there before a time, and returns what went. A file
   * that can't be read is left for an admin to look at.
   */
  public List<Kept> purge(Instant before) throws IOException {
    List<Kept> gone = new ArrayList<>();
    if (Files.notExists(recycle)) {
      return gone;
    }
    List<Path> files;
    try (var listed = Files.list(recycle)) {
      files = listed.filter(f -> f.getFileName().toString().endsWith(".dat")).sorted().toList();
    }
    for (Path file : files) {
      String name = file.getFileName().toString();
      Optional<Kept> kept;
      try {
        kept = read(recycle, name.substring(0, name.length() - 4));
      } catch (IOException e) {
        continue;
      }
      if (kept.isPresent() && kept.get().at().isBefore(before)) {
        Files.deleteIfExists(file);
        gone.add(kept.get());
      }
    }
    return gone;
  }

  private static <T> T value(Map<String, Object> root, String key, Class<T> type)
      throws IOException {
    Object value = root.get(key);
    if (!type.isInstance(value)) {
      throw new IOException("A kept item is damaged: no " + key);
    }
    return type.cast(value);
  }

  private static void write(Path folder, Kept kept) throws IOException {
    if (!ID.matcher(kept.id()).matches()) {
      throw new IOException("Not a curation id: " + kept.id());
    }
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("format-version", 1);
    root.put("id", kept.id());
    root.put("owner", kept.owner().toString());
    root.put("name", kept.name());
    root.put("season", kept.season());
    root.put("slot", kept.slot().key());
    root.put("curator", kept.curator());
    root.put("reason", kept.reason());
    root.put("at", kept.at().toEpochMilli());
    root.put("item", kept.item().nbt());
    Files.createDirectories(folder);
    AtomicFiles.write(folder.resolve(kept.id() + ".dat"), Nbt.writeCompressed(root));
  }

  private static Optional<Kept> read(Path folder, String id) throws IOException {
    if (!ID.matcher(id).matches()) {
      return Optional.empty();
    }
    Path file = folder.resolve(id + ".dat");
    if (Files.notExists(file)) {
      return Optional.empty();
    }
    Map<String, Object> root = Nbt.read(Files.readAllBytes(file));
    try {
      return Optional.of(
          new Kept(
              value(root, "id", String.class),
              new PlayerId(UUID.fromString(value(root, "owner", String.class))),
              value(root, "name", String.class),
              value(root, "season", Integer.class),
              ArchiveSlot.parse(value(root, "slot", String.class))
                  .orElseThrow(() -> new IOException("A kept item with no slot: " + file)),
              value(root, "curator", String.class),
              value(root, "reason", String.class),
              Instant.ofEpochMilli(value(root, "at", Long.class)),
              new NeutralItem(value(root, "item", byte[].class))));
    } catch (IllegalArgumentException e) {
      throw new IOException("A kept item is damaged: " + file, e);
    }
  }
}
