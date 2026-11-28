// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.ConfigurationOptions;
import org.spongepowered.configurate.objectmapping.ObjectMapper;
import org.spongepowered.configurate.serialize.ScalarSerializer;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializerCollection;
import org.spongepowered.configurate.util.NamingSchemes;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Seasons Web's own files in {@code plugins/SeasonsWeb}: {@code web.yml} and {@code messages.yml},
 * written from the bundled copies when missing and read through Configurate, with anything left out
 * taken from the bundled copy.
 */
final class SettingsFile {
  static final String WEB = "web.yml";
  static final String MESSAGES = "messages.yml";

  private SettingsFile() {}

  /** Writes the bundled file if the folder has none. */
  static void writeDefault(Path folder, String name) throws IOException {
    Path file = folder.resolve(name);
    if (Files.exists(file)) {
      return;
    }
    Files.createDirectories(folder);
    try (InputStream in = bundled(name)) {
      Files.copy(in, file);
    }
  }

  /** web.yml, with the bundled values for anything it leaves out. */
  static WebSettings load(Path folder) throws ConfigurateException {
    ConfigurationNode node = loadMerged(folder, WEB);
    WebSettings settings = node.get(WebSettings.class);
    if (settings == null) {
      throw new SerializationException("web.yml is empty");
    }
    return settings;
  }

  /** The bundled web.yml's settings. */
  static WebSettings defaults() {
    try {
      return Objects.requireNonNull(bundledNode(WEB).get(WebSettings.class));
    } catch (ConfigurateException e) {
      throw new IllegalStateException("The bundled web.yml doesn't read", e);
    }
  }

  /** A file's node, with the bundled values for anything it leaves out. */
  static ConfigurationNode loadMerged(Path folder, String name) throws ConfigurateException {
    Path file = folder.resolve(name);
    ConfigurationNode node =
        Files.exists(file)
            ? YamlConfigurationLoader.builder().path(file).defaultOptions(options()).build().load()
            : YamlConfigurationLoader.builder().defaultOptions(options()).build().createNode();
    node.mergeFrom(bundledNode(name));
    return node;
  }

  /**
   * Every value of a node by its dotted path, such as {@code proxy.trusted}: lists and other
   * scalars as their text, for telling what a reload changed.
   */
  static java.util.Map<String, String> flatten(ConfigurationNode node) {
    java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
    flatten(node, "", out);
    return out;
  }

  private static void flatten(ConfigurationNode node, String prefix, java.util.Map<String, String> out) {
    if (node.isMap()) {
      node.childrenMap().forEach((k, child) ->
          flatten(child, prefix.isEmpty() ? String.valueOf(k) : prefix + "." + k, out));
      if (node.childrenMap().isEmpty() && !prefix.isEmpty()) {
        out.put(prefix, "{}");
      }
    } else {
      out.put(prefix, String.valueOf(node.raw()));
    }
  }

  static ConfigurationNode bundledNode(String name) throws ConfigurateException {
    return YamlConfigurationLoader.builder()
        .source(
            () -> new BufferedReader(new InputStreamReader(bundled(name), StandardCharsets.UTF_8)))
        .defaultOptions(options())
        .build()
        .load();
  }

  private static InputStream bundled(String name) {
    return Objects.requireNonNull(
        // Beside this class, so no other plugin's file of the same name is found first.
        SettingsFile.class.getResourceAsStream(name), name + " is bundled");
  }

  private static ConfigurationOptions options() {
    TypeSerializerCollection serializers =
        TypeSerializerCollection.defaults()
            .childBuilder()
            .register(Duration.class, DurationSerializer.INSTANCE)
            .registerAnnotatedObjects(
                ObjectMapper.factoryBuilder()
                    .defaultNamingScheme(NamingSchemes.LOWER_CASE_DASHED)
                    .build())
            .build();
    return ConfigurationOptions.defaults().serializers(serializers);
  }

  private static final class DurationSerializer extends ScalarSerializer<Duration> {
    static final DurationSerializer INSTANCE = new DurationSerializer();

    private DurationSerializer() {
      super(Duration.class);
    }

    @Override
    public Duration deserialize(Type type, Object value) throws SerializationException {
      try {
        return DurationText.parse(value.toString());
      } catch (IllegalArgumentException e) {
        throw new SerializationException(type, e.getMessage());
      }
    }

    @Override
    protected Object serialize(Duration item, Predicate<Class<?>> typeSupported) {
      return DurationText.format(item);
    }
  }
}
