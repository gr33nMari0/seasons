// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes {@code site/src/data/commands/web.json}, the public docs' reference for {@code /web} (M9),
 * by walking the Brigadier tree {@link PaperWebCommands} registers, with each command's help from
 * {@code help.web.<word>} in messages.yml. {@code WebCommandReferenceTest} fails until the
 * committed file matches: run {@code ./gradlew commandReference}.
 */
public final class WebCommandReference {
  private WebCommandReference() {}

  public static void main(String[] args) throws IOException {
    Path out = Path.of(args[0]);
    Files.createDirectories(out.getParent());
    Files.writeString(out, json(Files.createTempDirectory("web-reference")), StandardCharsets.UTF_8);
  }

  /** A command for the reference: its literal words, usage lines and help. */
  record Entry(List<String> words, List<String> usages, String description) {}

  static List<Entry> entries(Path folder) throws IOException {
    SettingsFile.writeDefault(folder, SettingsFile.MESSAGES);
    Messages messages = Messages.load(folder);
    @SuppressWarnings("NullAway")
    CommandNode<CommandSourceStack> root = new PaperWebCommands(null, () -> null, () -> null).node();
    List<Entry> entries = new ArrayList<>();
    for (CommandNode<CommandSourceStack> child : root.getChildren()) {
      List<String> usages = new ArrayList<>();
      usages(child, "/web " + child.getName(), usages);
      String key = "help.web." + child.getName();
      entries.add(new Entry(List.of("web", child.getName()), usages, messages.get(key)));
    }
    return entries;
  }

  /** Each runnable path under a node, arguments as {@code <name>}. */
  private static void usages(CommandNode<CommandSourceStack> node, String line, List<String> out) {
    if (node.getCommand() != null) {
      out.add(line);
    }
    for (CommandNode<CommandSourceStack> child : node.getChildren()) {
      String word = child instanceof ArgumentCommandNode<?, ?> ? "<" + child.getName() + ">" : child.getName();
      usages(child, line + " " + word, out);
    }
  }

  static String json(Path folder) throws IOException {
    List<Entry> entries = entries(folder);
    StringBuilder out = new StringBuilder("{\n  \"plugin\": \"Seasons Web\",\n  \"commands\": [\n");
    for (int i = 0; i < entries.size(); i++) {
      Entry e = entries.get(i);
      out.append("    {\"words\": ")
          .append(array(e.words()))
          .append(", \"usages\": ")
          .append(array(e.usages()))
          .append(", \"description\": ")
          .append(string(e.description()))
          .append(", \"permissions\": ")
          .append(array(List.of(PaperWebCommands.PERMISSION)))
          .append('}')
          .append(i + 1 < entries.size() ? ",\n" : "\n");
    }
    return out.append("  ]\n}\n").toString();
  }

  private static String array(List<String> values) {
    List<String> quoted = values.stream().map(WebCommandReference::string).toList();
    return "[" + String.join(", ", quoted) + "]";
  }

  private static String string(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
