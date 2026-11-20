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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * {@code data/vaults/<uuid>.dat}: a player's ender vault, one page per season holding the ender
 * chest as it was at rollover, a Returned page for items handed back to them, and the history of
 * withdrawals. Writes are atomic and run on the writer thread.
 */
public final class VaultFiles {
  /** The {@code format-version} this build reads and writes. */
  public static final int FORMAT_VERSION = 1;

  /** The page number the Returned page goes by, as no season is numbered 0. */
  public static final int RETURNED = 0;

  private final Path directory;

  /**
   * @param directory {@code <world>/seasons/data/vaults}
   */
  public VaultFiles(Path directory) {
    this.directory = directory;
  }

  /**
   * Everything in one vault.
   *
   * @param pages each season's page, newest season first
   * @param returned the Returned page, with empty slots where items were taken
   */
  public record Contents(
      SortedMap<Integer, List<Optional<NeutralItem>>> pages, List<Optional<NeutralItem>> returned) {
    public Contents {
      SortedMap<Integer, List<Optional<NeutralItem>>> copy =
          new TreeMap<>(Collections.reverseOrder());
      pages.forEach((season, items) -> copy.put(season, List.copyOf(items)));
      pages = Collections.unmodifiableSortedMap(copy);
      returned = List.copyOf(returned);
    }

    /** Whether there is nothing in it to see. */
    public boolean isEmpty() {
      return pages.values().stream().flatMap(List::stream).allMatch(Optional::isEmpty)
          && returned.stream().allMatch(Optional::isEmpty);
    }

    /** One page by its number: a season, or {@link #RETURNED}. */
    public List<Optional<NeutralItem>> page(int page) {
      return page == RETURNED ? returned : pages.getOrDefault(page, List.of());
    }
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
    write(player, root);
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
    write(player, root);
    return true;
  }

  /** One season's page, if sealed, with empty slots where items were taken. */
  public Optional<List<Optional<NeutralItem>>> page(PlayerId player, int season)
      throws IOException {
    if (Files.notExists(path(player))) {
      return Optional.empty();
    }
    return pageOf(load(player), season);
  }

  /** Every page and the Returned page; empty contents if the player has no vault. */
  public Contents read(PlayerId player) throws IOException {
    SortedMap<Integer, List<Optional<NeutralItem>>> pages = new TreeMap<>();
    if (Files.notExists(path(player))) {
      return new Contents(pages, List.of());
    }
    Map<String, Object> root = load(player);
    for (String season : pages(root).keySet()) {
      int number = Integer.parseInt(season);
      pageOf(root, number).ifPresent(items -> pages.put(number, items));
    }
    return new Contents(pages, returned(root));
  }

  /** Adds an item to the Returned page, in its first empty slot. */
  public void addReturned(PlayerId player, NeutralItem item, Instant at) throws IOException {
    var _ = addReturned(player, item, at, Optional.empty());
  }

  /**
   * Adds an item to the Returned page, in its first empty slot, and resolves with that slot. With a
   * reference, such as {@code egg-s3}, the vault remembers it was given this item, and adding it
   * again changes nothing and resolves with the slot it went to the first time.
   */
  public int addReturned(PlayerId player, NeutralItem item, Instant at, Optional<String> ref)
      throws IOException {
    Map<String, Object> root = load(player);
    if (ref.isPresent()) {
      Optional<Map<String, Object>> was = delivery(root, ref.get());
      if (was.isPresent()) {
        return ArchiveFiles.value(was.get(), "slot", Integer.class);
      }
    }
    List<Optional<NeutralItem>> returned = new ArrayList<>(returned(root));
    int free = returned.indexOf(Optional.<NeutralItem>empty());
    if (free >= 0) {
      returned.set(free, Optional.of(item));
    } else {
      free = returned.size();
      returned.add(Optional.of(item));
    }
    root.put("returned", ArchiveFiles.slots(returned));
    root.put("returned-size", returned.size());
    root.put("returned-at", at.toEpochMilli());
    if (ref.isPresent()) {
      List<Map<String, Object>> deliveries = new ArrayList<>(deliveries(root));
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("ref", ref.get());
      entry.put("slot", free);
      entry.put("at", at.toEpochMilli());
      deliveries.add(entry);
      root.put("deliveries", NbtList.ofCompounds(deliveries));
    }
    write(player, root);
    return free;
  }

  /**
   * Whether the vault was given the item with this reference, whether it is still on the Returned
   * page or has been taken since.
   */
  public boolean delivered(PlayerId player, String ref) throws IOException {
    return Files.exists(path(player)) && delivery(load(player), ref).isPresent();
  }

  /** The Returned slot the item with this reference went to, if the vault was given it. */
  public OptionalInt returnedSlot(PlayerId player, String ref) throws IOException {
    if (Files.notExists(path(player))) {
      return OptionalInt.empty();
    }
    return delivery(load(player), ref)
        .map(e -> OptionalInt.of(ArchiveFiles.value(e, "slot", Integer.class)))
        .orElse(OptionalInt.empty());
  }

  private static Optional<Map<String, Object>> delivery(Map<String, Object> root, String ref) {
    return deliveries(root).stream().filter(e -> ref.equals(e.get("ref"))).findFirst();
  }

  private static List<Map<String, Object>> deliveries(Map<String, Object> root) {
    return root.get("deliveries") instanceof NbtList list ? list.compounds() : List.of();
  }

  /**
   * Takes the item out of one slot and records the withdrawal under {@code id}, so it can be rolled
   * back. Empty if the slot is empty or the withdrawal is already recorded.
   *
   * @param page a season, or {@link #RETURNED}
   */
  public Optional<NeutralItem> withdraw(PlayerId player, int page, int slot, String id, Instant at)
      throws IOException {
    if (Files.notExists(path(player))) {
      return Optional.empty();
    }
    Map<String, Object> root = load(player);
    if (find(root, id).isPresent()) {
      return Optional.empty();
    }
    List<Optional<NeutralItem>> items = new ArrayList<>(pageOrReturned(root, page));
    if (slot < 0 || slot >= items.size() || items.get(slot).isEmpty()) {
      return Optional.empty();
    }
    NeutralItem item = items.get(slot).get();
    items.set(slot, Optional.empty());
    setPage(root, page, items);
    List<Map<String, Object>> history = new ArrayList<>(history(root));
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("id", id);
    entry.put("page", page);
    entry.put("slot", slot);
    entry.put("at", at.toEpochMilli());
    entry.put("item", ArchiveFiles.item(item));
    history.add(entry);
    root.put("withdrawals", NbtList.ofCompounds(history));
    write(player, root);
    return Optional.of(item);
  }

  /** Whether a withdrawal is recorded, and so its item is out of the vault. */
  public boolean withdrawn(PlayerId player, String id) throws IOException {
    return Files.exists(path(player)) && find(load(player), id).isPresent();
  }

  /**
   * Puts a withdrawal's item back in its slot and forgets the withdrawal; false if there is no such
   * withdrawal. If the slot has been filled since, the item goes to the Returned page.
   */
  public boolean rollBack(PlayerId player, String id) throws IOException {
    if (Files.notExists(path(player))) {
      return false;
    }
    Map<String, Object> root = load(player);
    Optional<Map<String, Object>> entry = find(root, id);
    if (entry.isEmpty()) {
      return false;
    }
    int page = ArchiveFiles.value(entry.get(), "page", Integer.class);
    int slot = ArchiveFiles.value(entry.get(), "slot", Integer.class);
    NeutralItem item =
        new NeutralItem(Nbt.writeCompressed(ArchiveFiles.compound(entry.get(), "item")));
    List<Optional<NeutralItem>> items = new ArrayList<>(pageOrReturned(root, page));
    if (page != RETURNED && pageOf(root, page).isEmpty()) {
      // The page itself is gone (unarchived): the item comes back on the Returned page.
      page = RETURNED;
      items = new ArrayList<>(returned(root));
      slot = items.size();
    }
    while (items.size() <= slot) {
      items.add(Optional.empty());
    }
    if (items.get(slot).isPresent()) {
      page = RETURNED;
      items = new ArrayList<>(returned(root));
      int free = items.indexOf(Optional.<NeutralItem>empty());
      slot = free >= 0 ? free : items.size();
      if (slot == items.size()) {
        items.add(Optional.empty());
      }
    }
    items.set(slot, Optional.of(item));
    setPage(root, page, items);
    List<Map<String, Object>> history = new ArrayList<>(history(root));
    history.removeIf(e -> id.equals(e.get("id")));
    root.put("withdrawals", NbtList.ofCompounds(history));
    write(player, root);
    return true;
  }

  private void write(PlayerId player, Map<String, Object> root) throws IOException {
    AtomicFiles.write(path(player), Nbt.writeCompressed(root));
  }

  private static Optional<Map<String, Object>> find(Map<String, Object> root, String id) {
    return history(root).stream().filter(e -> id.equals(e.get("id"))).findFirst();
  }

  private static List<Map<String, Object>> history(Map<String, Object> root) {
    return root.get("withdrawals") instanceof NbtList list ? list.compounds() : List.of();
  }

  private static List<Optional<NeutralItem>> pageOrReturned(Map<String, Object> root, int page) {
    return page == RETURNED ? returned(root) : pageOf(root, page).orElse(List.of());
  }

  private static void setPage(
      Map<String, Object> root, int page, List<Optional<NeutralItem>> items) {
    if (page == RETURNED) {
      root.put("returned", ArchiveFiles.slots(items));
      root.put("returned-size", items.size());
      return;
    }
    Map<String, Object> pages = pages(root);
    @SuppressWarnings("unchecked")
    Map<String, Object> was = (Map<String, Object>) pages.get(Integer.toString(page));
    Map<String, Object> next = new LinkedHashMap<>(was);
    next.put("items", ArchiveFiles.slots(items));
    next.put("size", items.size());
    pages.put(Integer.toString(page), next);
  }

  @SuppressWarnings("unchecked")
  private static Optional<List<Optional<NeutralItem>>> pageOf(
      Map<String, Object> root, int season) {
    Object page = pages(root).get(Integer.toString(season));
    if (!(page instanceof Map<?, ?> map)) {
      return Optional.empty();
    }
    Map<String, Object> values = (Map<String, Object>) map;
    return Optional.of(
        ArchiveFiles.slots(
            values.getOrDefault("items", NbtList.ofCompounds(List.of())),
            ArchiveFiles.value(values, "size", Integer.class)));
  }

  private static List<Optional<NeutralItem>> returned(Map<String, Object> root) {
    int size = root.get("returned-size") instanceof Integer n ? n : -1;
    return ArchiveFiles.slots(root.getOrDefault("returned", NbtList.ofCompounds(List.of())), size);
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
