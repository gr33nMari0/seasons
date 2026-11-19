// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import com.petepeterepeat.seasons.core.command.ArgumentType;
import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.store.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /season config get <path>} and {@code set <path> <value>}: config.yml's settings, by key
 * path such as {@code hof.entry.cooldown}. Get shows the value in force; set writes it back into
 * config.yml in place ({@link YamlEdits}), through {@link ConfigEdits}, so it is validated, every
 * file is loaded again and swapped in, the file is put back if it doesn't take, and the change is
 * audited.
 *
 * <p>Only the settings the bundled config.yml lists are reachable, so neither secrets.yml, the
 * environment, nor anything else pasted into config.yml is ever shown; and a path that looks like a
 * secret is refused before anything is read.
 */
public final class ConfigCommands {
  /** Permission for the config commands. */
  public static final String PERMISSION = "seasons.admin.config";

  /** Words in a path that mean a secret: refused whether or not such a setting exists. */
  private static final List<String> SECRET_WORDS =
      List.of("webhook", "secret", "token", "password");

  private final ConfigHolder config;
  private final ConfigLoader loader;
  private final ConfigEdits edits;
  private final Path backups;

  /**
   * @param backups where the previous copies of config.yml are kept
   */
  public ConfigCommands(ConfigHolder config, ConfigLoader loader, ConfigEdits edits, Path backups) {
    this.config = config;
    this.loader = loader;
    this.edits = edits;
    this.backups = backups;
  }

  /** The node under {@code /season}: config get and set. */
  public CommandNode node() {
    return CommandNode.literal("config")
        .permission(PERMISSION)
        .then(
            CommandNode.literal("get")
                .then(CommandNode.argument("path", ArgumentType.word()).executes(this::get)))
        .then(
            CommandNode.literal("set")
                .then(
                    CommandNode.argument("path", ArgumentType.word())
                        .then(
                            CommandNode.argument("value", ArgumentType.greedy())
                                .executes(this::set))))
        .build();
  }

  void get(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    String path = arguments.string("path");
    if (secret(path)) {
      source.reply(messages.get("config.secret"));
      return;
    }
    Map<String, String> settings = config.get().settings();
    String value = settings.get(path);
    if (value != null) {
      source.reply(messages.get("config.value", "path", path, "value", value));
      return;
    }
    List<String> section = section(settings, path);
    if (section.isEmpty()) {
      source.reply(messages.get("config.unknown", "path", path));
      return;
    }
    source.reply(messages.get("config.section", "path", path));
    section.forEach(key ->
        source.reply(
            messages.get("config.value", "path", key, "value", settings.getOrDefault(key, ""))));
  }

  void set(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    String path = arguments.string("path");
    if (secret(path)) {
      source.reply(messages.get("config.secret"));
      return;
    }
    Map<String, String> settings = config.get().settings();
    List<String> section = section(settings, path);
    if (!section.isEmpty()) {
      source.reply(messages.get("config.is-section", "path", path, "example", section.getFirst()));
      return;
    }
    String before = settings.get(path);
    if (before == null) {
      source.reply(messages.get("config.unknown", "path", path));
      return;
    }
    String value;
    try {
      value = YamlEdits.value(arguments.string("value").strip());
    } catch (YamlEdits.Refused e) {
      source.reply(
          messages.get("config.not-set", "path", path, "error", String.valueOf(e.getMessage())));
      return;
    }
    if (value.equals(before)) {
      source.reply(messages.get("config.already", "path", path, "value", value));
      return;
    }
    Path file = loader.configFile();
    edits.edit(
        source,
        new ConfigEdits.Change(ConfigLoader.CONFIG + " " + path, before, value),
        file,
        () -> write(file, List.of(path.split("\\.")), value),
        "config.not-set",
        () ->
            source.reply(
                messages().get("config.set", "path", path, "value", value, "before", before)),
        "path",
        path);
  }

  /** On the writer thread: the value written into config.yml in place, keeping a backup. */
  private void write(Path file, List<String> path, String value) throws IOException {
    String text = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    String edited;
    try {
      edited = YamlEdits.set(text, path, value);
    } catch (YamlEdits.Unreadable e) {
      // Worded as /season reload words a file that doesn't parse.
      throw new IOException(
          ConfigProblem.at(
                  ConfigLoader.CONFIG,
                  e.line(),
                  Math.max(1, e.column()),
                  String.valueOf(e.getMessage()))
              .describe(messages()),
          e);
    } catch (YamlEdits.Refused e) {
      throw new IOException(ConfigLoader.CONFIG + ": " + e.getMessage(), e);
    }
    AtomicFiles.writeKeepingBackups(file, edited.getBytes(StandardCharsets.UTF_8), backups);
  }

  /** The settings under a path, if it names a section, in the bundled file's order. */
  private static List<String> section(Map<String, String> settings, String path) {
    return settings.keySet().stream().filter(key -> key.startsWith(path + ".")).toList();
  }

  /** Whether a path looks like a secret: secrets.yml's keys, or anything named like one. */
  static boolean secret(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    return lower.equals("secrets")
        || lower.startsWith("secrets.")
        || SECRET_WORDS.stream().anyMatch(lower::contains);
  }

  private Messages messages() {
    return config.get().messages();
  }
}
