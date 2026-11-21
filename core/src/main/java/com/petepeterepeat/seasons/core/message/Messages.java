// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.message;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Every player-facing string. The bundled messages.yml holds the defaults; the server's copy in the
 * config directory overrides any of them.
 */
public final class Messages {
  static final String FILE_NAME = "messages.yml";
  private static final String BUNDLED = "/com/petepeterepeat/seasons/core/messages.yml";
  private static final Logger LOG = LoggerFactory.getLogger(Messages.class);
  private static final Pattern TAG = Pattern.compile("</?[a-z_#!:][^<>]*>");

  private final Map<String, String> templates;

  Messages(Map<String, String> templates) {
    this.templates = Map.copyOf(templates);
  }

  /**
   * Loads messages, writing the bundled defaults to the config directory first if the server has no
   * messages.yml yet.
   *
   * @throws IOException if the file can't be read; a {@link
   *     org.spongepowered.configurate.loader.ParsingException} says where a syntax error is
   */
  public static Messages load(Path configDirectory) throws IOException {
    Path file = configDirectory.resolve(FILE_NAME);
    if (Files.notExists(file)) {
      Files.createDirectories(configDirectory);
      try (InputStream in = bundled()) {
        Files.copy(in, file);
      }
    }
    ConfigurationNode merged = YamlConfigurationLoader.builder().path(file).build().load();
    merged.mergeFrom(loadBundled());
    return new Messages(flatten(merged));
  }

  /** The bundled defaults only. */
  public static Messages defaults() {
    try {
      return new Messages(flatten(loadBundled()));
    } catch (ConfigurateException e) {
      throw new UncheckedIOException("Can't read the bundled " + FILE_NAME, e);
    }
  }

  /**
   * The message for a key, such as {@code status.header}.
   *
   * @param placeholders alternating placeholder names and values
   */
  public Message get(String key, String... placeholders) {
    if (placeholders.length % 2 != 0) {
      throw new IllegalArgumentException("Placeholders come in name, value pairs");
    }
    Map<String, String> values = new HashMap<>();
    for (int i = 0; i < placeholders.length; i += 2) {
      values.put(placeholders[i], placeholders[i + 1]);
    }
    String template = templates.get(key);
    if (template == null) {
      LOG.warn("No message for key {} in {}", key, FILE_NAME);
      return new Message("<red>Missing message: <key></red>", Map.of("key", key));
    }
    return new Message(template, values);
  }

  /** Whether messages.yml has a key, such as an award's name for an award of the server's own. */
  public boolean has(String key) {
    return templates.containsKey(key);
  }

  /**
   * A message as plain text, for Discord posts, logs and text placed inside another message's
   * placeholder. Placeholders are substituted as written; MiniMessage tags in the template are left
   * out.
   */
  public String text(String key, String... placeholders) {
    return plain(get(key, placeholders));
  }

  /** Any message as plain text, as {@link #text} describes. */
  public static String plain(Message message) {
    String out = message.template();
    for (Map.Entry<String, String> placeholder : message.placeholders().entrySet()) {
      out =
          out.replace("<" + placeholder.getKey() + ">", "\u0000" + placeholder.getKey() + "\u0000");
    }
    out = TAG.matcher(out).replaceAll("");
    for (Map.Entry<String, String> placeholder : message.placeholders().entrySet()) {
      out = out.replace("\u0000" + placeholder.getKey() + "\u0000", placeholder.getValue());
    }
    return out;
  }

  private static ConfigurationNode loadBundled() throws ConfigurateException {
    return YamlConfigurationLoader.builder()
        .source(() ->
            new java.io.BufferedReader(
                new java.io.InputStreamReader(bundled(), java.nio.charset.StandardCharsets.UTF_8)))
        .build()
        .load();
  }

  private static InputStream bundled() {
    return Objects.requireNonNull(
        Messages.class.getResourceAsStream(BUNDLED), "bundled " + FILE_NAME + " is missing");
  }

  private static Map<String, String> flatten(ConfigurationNode root) {
    Map<String, String> out = new HashMap<>();
    flatten(root, null, out);
    return out;
  }

  private static void flatten(
      ConfigurationNode node, @Nullable String prefix, Map<String, String> out) {
    if (node.isMap()) {
      node.childrenMap()
          .forEach((key, child) -> {
            String path = prefix == null ? String.valueOf(key) : prefix + "." + key;
            flatten(child, path, out);
          });
    } else if (prefix != null && node.rawScalar() instanceof String s) {
      out.put(prefix, s);
    }
  }
}
