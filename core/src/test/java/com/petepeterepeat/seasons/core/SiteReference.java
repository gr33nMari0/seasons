// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.command.CommandHelp;
import com.petepeterepeat.seasons.core.map.MapPublisher;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.yaml.snakeyaml.Yaml;

/**
 * Writes the public docs' generated references (M9, docs/plans/m9-design-overhaul.md) from the
 * code, so the site can't drift from it: every Seasons command with its usage lines, help text and
 * permission nodes into {@code site/src/data/commands/seasons.json}, and every permission node of
 * both plugins, from their {@code paper-plugin.yml}, into {@code site/src/data/permissions.json}.
 * {@link SiteReferenceTest} fails until the committed files match: run {@code ./gradlew
 * commandReference permissionReference}.
 */
public final class SiteReference {
  private SiteReference() {}

  public static void main(String[] args) throws IOException {
    Path data = Path.of(args[1]);
    Files.createDirectories(data.resolve("commands"));
    switch (args[0]) {
      case "commands" ->
          Files.writeString(data.resolve("commands/seasons.json"), commands(), StandardCharsets.UTF_8);
      case "permissions" ->
          Files.writeString(
              data.resolve("permissions.json"), permissions(Path.of(args[2])), StandardCharsets.UTF_8);
      default -> throw new IllegalArgumentException("commands or permissions, not " + args[0]);
    }
  }

  /** Every Seasons command as JSON, from the trees Seasons registers. */
  public static String commands() throws IOException {
    Path dir = Files.createTempDirectory("seasons-reference");
    FakePlatform fake = new FakePlatform();
    Seasons seasons =
        new Seasons(
            fake.platform(new Capabilities(EnumSet.allOf(Capability.class)), dir),
            "reference",
            fake.clock,
            Webhooks.NONE,
            MapPublisher.NONE,
            Map.of(),
            new SerialWriter(Runnable::run),
            Optional.empty());
    seasons.start();
    try {
      CommandHelp help = seasons.commandHelp().orElseThrow();
      StringBuilder out = new StringBuilder("{\n  \"plugin\": \"Seasons\",\n  \"commands\": [\n");
      List<CommandHelp.Entry> entries = help.reference();
      for (int i = 0; i < entries.size(); i++) {
        CommandHelp.Entry e = entries.get(i);
        out.append("    {\"words\": ")
            .append(array(e.words()))
            .append(", \"usages\": ")
            .append(array(e.usages()))
            .append(", \"description\": ")
            .append(string(plain(e.description())))
            .append(", \"permissions\": ")
            .append(array(e.permissions()))
            .append('}')
            .append(i + 1 < entries.size() ? ",\n" : "\n");
      }
      return out.append("  ]\n}\n").toString();
    } finally {
      seasons.stop();
    }
  }

  /** Both plugins' permission nodes as JSON: node, plugin, default, description and children. */
  @SuppressWarnings("unchecked")
  public static String permissions(Path repository) throws IOException {
    Map<String, Path> plugins = new LinkedHashMap<>();
    plugins.put("Seasons", repository.resolve("adapters/paper/src/main/resources/paper-plugin.yml"));
    plugins.put("Seasons Web", repository.resolve("web/src/main/resources/paper-plugin.yml"));
    List<String> rows = new ArrayList<>();
    for (Map.Entry<String, Path> plugin : plugins.entrySet()) {
      Map<String, Object> yaml = new Yaml().load(Files.readString(plugin.getValue()));
      Map<String, Map<String, Object>> nodes =
          (Map<String, Map<String, Object>>) yaml.getOrDefault("permissions", Map.of());
      for (Map.Entry<String, Map<String, Object>> node : nodes.entrySet()) {
        Map<String, Object> children =
            (Map<String, Object>) node.getValue().getOrDefault("children", Map.of());
        rows.add(
            "    {\"node\": "
                + string(node.getKey())
                + ", \"plugin\": "
                + string(plugin.getKey())
                + ", \"default\": "
                + string(String.valueOf(node.getValue().getOrDefault("default", "op")))
                + ", \"description\": "
                + string(String.valueOf(node.getValue().getOrDefault("description", "")))
                + ", \"children\": "
                + array(List.copyOf(children.keySet()))
                + "}");
      }
    }
    return "{\n  \"permissions\": [\n" + String.join(",\n", rows) + "\n  ]\n}\n";
  }

  /** Help text as plain words: MiniMessage tags taken out. */
  static String plain(String text) {
    return text.replaceAll("</?[a-z_:#][^<>]*>", "").strip();
  }

  static String array(List<String> values) {
    StringBuilder out = new StringBuilder("[");
    for (int i = 0; i < values.size(); i++) {
      out.append(i == 0 ? "" : ", ").append(string(values.get(i)));
    }
    return out.append(']').toString();
  }

  static String string(String value) {
    StringBuilder out = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }
}
