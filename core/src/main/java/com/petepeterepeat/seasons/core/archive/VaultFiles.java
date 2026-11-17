// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.archive;

import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.store.AtomicFiles;
import com.petepeterepeat.seasons.core.store.nbt.Nbt;
import com.petepeterepeat.seasons.core.store.nbt.Nbt.NbtList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code data/vaults/<uuid>.dat}: a player's ender vault, one page per season holding the ender
 * chest as it was at rollover. M1 seals pages; viewing and withdrawing arrive with M3. Writes are
 * atomic and run on the writer thread.
 */
public final class VaultFiles {
  /** The {@code format-version} this build reads and writes. */
  public static final int FORMAT_VERSION = 1;

  private final Path directory;

  /**
   * @param directory {@code <world>/seasons/data/vaults}
   */
  public VaultFiles(Path directory) {
    this.directory = directory;
  }

  public Path path(PlayerId player) {
    return directory.resolve(player + ".dat");
  }

  /**
   * Seals one season's page, replacing any page already sealed for that season, so sealing twice
   * leaves one page.
   */
  public void seal(
      PlayerId player,
      int season,
      List<Optional<NeutralItem>> items,
      String hash,
      Instant at,
      int dataVersion)
      throws IOException {
    Map<String, Object> root = load(player);
    Map<String, Object> pages = pages(root);
    Map<String, Object> page = new LinkedHashMap<>();
    page.put("sealed", at.toEpochMilli());
    page.put("hash", hash);
    page.put("size", items.size());
    page.put("items", ArchiveFiles.slots(items));
    pages.put(Integer.toString(season), page);
    root.put("DataVersion", dataVersion);
    AtomicFiles.write(path(player), Nbt.writeCompressed(root));
  }

  /** Removes one season's page, as {@code /season unarchive} does. */
  public boolean remove(PlayerId player, int season) throws IOException {
    if (Files.notExists(path(player))) {
      return false;
    }
    Map<String, Object> root = load(player);
    if (pages(root).remove(Integer.toString(season)) == null) {
      return false;
    }
    AtomicFiles.write(path(player), Nbt.writeCompressed(root));
    return true;
  }

  /** One season's page, if sealed. */
  @SuppressWarnings("unchecked")
  public Optional<List<Optional<NeutralItem>>> page(PlayerId player, int season)
      throws IOException {
    if (Files.notExists(path(player))) {
      return Optional.empty();
    }
    Object page = pages(load(player)).get(Integer.toString(season));
    if (!(page instanceof Map<?, ?> map)) {
      return Optional.empty();
    }
    Map<String, Object> values = (Map<String, Object>) map;
    return Optional.of(
        ArchiveFiles.slots(
            values.getOrDefault("items", NbtList.ofCompounds(List.of())),
            ArchiveFiles.value(values, "size", Integer.class)));
  }

  private Map<String, Object> load(PlayerId player) throws IOException {
    Path file = path(player);
    if (Files.notExists(file)) {
      Map<String, Object> root = new LinkedHashMap<>();
      root.put("format-version", FORMAT_VERSION);
      root.put("player", player.toString());
      root.put("pages", new LinkedHashMap<String, Object>());
      root.put("returned", NbtList.ofCompounds(List.of()));
      root.put("withdrawals", NbtList.ofCompounds(List.of()));
      return root;
    }
    Map<String, Object> root = new LinkedHashMap<>(Nbt.read(Files.readAllBytes(file)));
    int version = root.get("format-version") instanceof Integer v ? v : 0;
    if (version < 1 || version > FORMAT_VERSION) {
      throw new IOException("Vault " + file + " has format-version " + version);
    }
    return root;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> pages(Map<String, Object> root) {
    Map<String, Object> pages =
        new LinkedHashMap<>((Map<String, Object>) root.getOrDefault("pages", Map.of()));
    root.put("pages", pages);
    return pages;
  }
}
