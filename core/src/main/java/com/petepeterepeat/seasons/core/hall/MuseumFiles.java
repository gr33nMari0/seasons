// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.store.AtomicFiles;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * The museum's store: {@code museum.yml} holds each trophy's label, season and credit, and {@code
 * data/museum/<gallery>.<cell>.dat} its item as the platform wrote it, vanilla NBT with its {@code
 * DataVersion}. An item taken out waits under {@code removed} and {@code data/museum/removed/} until
 * its withdrawal is settled, so a crash can put it back. Used on the writer thread only.
 *
 * <pre>
 * schema-version: 1
 * trophies:
 *   "museum:core": { label: Heavy core, season: 3, credit: Peter, at: "2026-11-20T...", id: ... }
 * removed:
 *   "1763...-2": { anchor: "museum:core", label: Heavy core, season: 3, credit: Peter, ... }
 * </pre>
 */
public final class MuseumFiles {
  private static final int SCHEMA_VERSION = 1;

  private final Path file;
  private final Path data;

  /**
   * One trophy.
   *
   * @param anchor the plinth, as {@code gallery:cell}
   * @param credit who put it there
   * @param id the transaction that put it there, or took it out
   */
  public record Trophy(
      String anchor, Optional<String> label, int season, String credit, Instant at, String id) {}

  /**
   * @param file {@code <world>/seasons/museum.yml}
   * @param data {@code <world>/seasons/data/museum}
   */
  public MuseumFiles(Path file, Path data) {
    this.file = file;
    this.data = data;
  }

  /** Every trophy on a plinth, by anchor. */
  public Map<String, Trophy> trophies() throws IOException {
    return section(load().node("trophies"));
  }

  public Optional<Trophy> trophy(String anchor) throws IOException {
    return Optional.ofNullable(trophies().get(anchor));
  }

  /** A trophy's item, if it has one. */
  public Optional<NeutralItem> item(String anchor) throws IOException {
    Path at = itemPath(anchor);
    return Files.exists(at) ? Optional.of(new NeutralItem(Files.readAllBytes(at))) : Optional.empty();
  }

  /** Puts a trophy on its plinth: the item first, then its line in museum.yml. */
  public void put(Trophy trophy, NeutralItem item) throws IOException {
    Files.createDirectories(data);
    AtomicFiles.write(itemPath(trophy.anchor()), item.nbt());
    ConfigurationNode root = load();
    set(root.node("trophies", trophy.anchor()), trophy);
    save(root);
  }

  /** Takes a trophy off its plinth for good, as a crash undoing {@code /museum add} does. */
  public void delete(String anchor) throws IOException {
    ConfigurationNode root = load();
    root.node("trophies").removeChild(anchor);
    save(root);
    Files.deleteIfExists(itemPath(anchor));
  }

  /**
   * Takes a trophy off its plinth into {@code removed}, under the withdrawal's id, and returns its
   * item. Empty if the plinth holds nothing.
   */
  public Optional<NeutralItem> takeOut(String anchor, String id) throws IOException {
    Optional<Trophy> trophy = trophy(anchor);
    Optional<NeutralItem> item = item(anchor);
    if (trophy.isEmpty() || item.isEmpty()) {
      return Optional.empty();
    }
    Files.createDirectories(removedPath(id).getParent());
    AtomicFiles.write(removedPath(id), item.get().nbt());
    ConfigurationNode root = load();
    root.node("trophies").removeChild(anchor);
    Trophy t = trophy.get();
    set(root.node("removed", id), new Trophy(t.anchor(), t.label(), t.season(), t.credit(), t.at(), id));
    save(root);
    Files.deleteIfExists(itemPath(anchor));
    return item;
  }

  /** Whether a withdrawal is waiting under {@code removed}. */
  public boolean takenOut(String id) throws IOException {
    return !load().node("removed", id).virtual();
  }

  /** Puts a withdrawn trophy back on its plinth; false if there is no such withdrawal. */
  public boolean putBack(String id) throws IOException {
    ConfigurationNode root = load();
    Optional<Trophy> trophy = Optional.ofNullable(section(root.node("removed")).get(id));
    if (trophy.isEmpty() || Files.notExists(removedPath(id))) {
      return false;
    }
    put(trophy.get(), new NeutralItem(Files.readAllBytes(removedPath(id))));
    forget(id);
    return true;
  }

  /** Forgets a withdrawal once its item is given and saved. */
  public void forget(String id) throws IOException {
    ConfigurationNode root = load();
    root.node("removed").removeChild(id);
    save(root);
    Files.deleteIfExists(removedPath(id));
  }

  private Path itemPath(String anchor) {
    return data.resolve(anchor.replace(':', '.') + ".dat");
  }

  private Path removedPath(String id) {
    return data.resolve("removed").resolve(id + ".dat");
  }

  private static Map<String, Trophy> section(ConfigurationNode node) {
    Map<String, Trophy> found = new LinkedHashMap<>();
    for (Map.Entry<Object, ? extends ConfigurationNode> entry : node.childrenMap().entrySet()) {
      ConfigurationNode t = entry.getValue();
      String key = String.valueOf(entry.getKey());
      String text = t.node("at").getString();
      found.put(
          key,
          new Trophy(
              t.node("anchor").getString(key),
              Optional.ofNullable(t.node("label").getString()),
              t.node("season").getInt(0),
              t.node("credit").getString(""),
              text == null ? Instant.EPOCH : Instant.parse(text),
              t.node("id").getString(key)));
    }
    return found;
  }

  private static void set(ConfigurationNode node, Trophy trophy)
      throws org.spongepowered.configurate.serialize.SerializationException {
    node.node("anchor").set(trophy.anchor());
    if (trophy.label().isPresent()) {
      node.node("label").set(trophy.label().get());
    }
    node.node("season").set(trophy.season());
    node.node("credit").set(trophy.credit());
    node.node("at").set(trophy.at().toString());
    node.node("id").set(trophy.id());
  }

  private ConfigurationNode load() throws IOException {
    if (Files.notExists(file)) {
      ConfigurationNode root = loader(null).createNode();
      root.node("schema-version").set(SCHEMA_VERSION);
      return root;
    }
    String text = Files.readString(file, UTF_8);
    return YamlConfigurationLoader.builder()
        .source(() -> new BufferedReader(new StringReader(text)))
        .build()
        .load();
  }

  private void save(ConfigurationNode root) throws IOException {
    StringWriter out = new StringWriter();
    loader(out).save(root);
    Files.createDirectories(file.getParent());
    AtomicFiles.write(file, out.toString().getBytes(UTF_8));
  }

  private static YamlConfigurationLoader loader(@Nullable StringWriter out) {
    YamlConfigurationLoader.Builder builder =
        YamlConfigurationLoader.builder().nodeStyle(NodeStyle.BLOCK);
    if (out != null) {
      builder.sink(() -> new BufferedWriter(out));
    }
    return builder.build();
  }
}
