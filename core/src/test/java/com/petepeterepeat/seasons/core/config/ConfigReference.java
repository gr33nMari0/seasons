// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.objectmapping.meta.Comment;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * The config reference, generated from the code (a build tool, in the tests: nothing of it ships).
 * Each setting's {@link Comment} on its record component is the one description of it. From those
 * and the defaults in the bundled files this writes the bundled {@code config.yml} and {@code
 * secrets.template.yml}, comments and all, and {@code docs/config.md}. Run {@code ./gradlew
 * :core:configReference} after changing a setting; {@code ConfigReferenceTest} fails until the
 * files match.
 */
public final class ConfigReference {
  private static final String RESOURCES = "/com/petepeterepeat/seasons/core/";
  private static final int WIDTH = 100;

  /** Plain YAML scalars that need no quotes. */
  private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.:/+-]*");

  /** Plain scalars YAML would read as something other than a string. */
  private static final List<String> RESERVED =
      List.of("true", "false", "yes", "no", "on", "off", "null", "~", "y", "n");

  private static final String CONFIG_HEADER =
      """
      Seasons settings. Every key and its default is listed here; edit, then /season reload. \
      Durations are written like 7d, 1h30m, 10m or 10s. A server's file only needs the keys it \
      changes. Reference: docs/config.md, generated with this file from the code.\
      """;

  private static final String SECRETS_HEADER =
      """
      Seasons secrets. Never paste this file anywhere, and keep it out of version control. The \
      environment variables SEASONS_DISCORD_WEBHOOK and SEASONS_ADMIN_WEBHOOK override these. \
      Webhook URLs must use https. Leave a value empty to turn it off. Then /season reload.\
      """;

  private ConfigReference() {}

  /** Writes the bundled files and the reference: {@code <resources dir> <docs/config.md>}. */
  public static void main(String[] args) throws IOException {
    Path resources = Path.of(args[0]);
    Files.writeString(resources.resolve("config.yml"), configYaml());
    Files.writeString(resources.resolve("secrets.template.yml"), secretsYaml());
    Files.writeString(Path.of(args[1]), markdown());
  }

  /** The bundled {@code config.yml}: every setting with its comment and default. */
  public static String configYaml() {
    return yaml(CONFIG_HEADER, SeasonsConfig.class, bundled("config.yml"));
  }

  /** The bundled {@code secrets.template.yml}, which a new server's {@code secrets.yml} copies. */
  public static String secretsYaml() {
    return yaml(SECRETS_HEADER, Secrets.class, bundled("secrets.template.yml"));
  }

  /** Every setting with no {@link Comment}, as its path; empty when all are described. */
  public static List<String> undocumented() {
    List<String> missing = new ArrayList<>();
    undocumented(SeasonsConfig.class, "", missing);
    undocumented(Secrets.class, "", missing);
    return missing;
  }

  private static void undocumented(Class<?> type, String prefix, List<String> missing) {
    for (RecordComponent component : type.getRecordComponents()) {
      String path = prefix + component.getName();
      if (comment(component) == null) {
        missing.add(path);
      }
      Class<?> nested = section(component);
      if (nested != null) {
        undocumented(nested, path + ".", missing);
      }
      Class<?> entries = mapValues(component);
      if (entries != null) {
        undocumented(entries, path + ".<id>.", missing);
      }
    }
  }

  // ----- config.yml -----

  private static String yaml(String header, Class<?> type, ConfigurationNode values) {
    StringBuilder out = new StringBuilder();
    comment(out, "", header);
    out.append("schema-version: ").append(values.node("schema-version").getInt(1)).append('\n');
    for (RecordComponent component : type.getRecordComponents()) {
      out.append('\n');
      entry(out, "", component, values.node(component.getName()));
    }
    return out.toString();
  }

  private static void entry(
      StringBuilder out, String indent, RecordComponent component, ConfigurationNode value) {
    comment(out, indent, describe(component));
    String key = component.getName();
    Class<?> nested = section(component);
    if (nested != null) {
      out.append(indent).append(key).append(":\n");
      for (RecordComponent child : nested.getRecordComponents()) {
        entry(out, indent + "  ", child, value.node(child.getName()));
      }
    } else if (value.isMap()) {
      // A map of records, such as awards: one entry a line, in the bundled order.
      out.append(indent).append(key).append(":\n");
      for (Map.Entry<Object, ? extends ConfigurationNode> child : value.childrenMap().entrySet()) {
        out.append(indent)
            .append("  ")
            .append(child.getKey())
            .append(": ")
            .append(flow(child.getValue()))
            .append('\n');
      }
    } else {
      out.append(indent).append(key).append(": ").append(flow(value)).append('\n');
    }
  }

  /** A value on one line: a scalar, a list in brackets or a map in braces. */
  static String flow(ConfigurationNode value) {
    if (value.isList()) {
      return value.childrenList().stream()
          .map(ConfigReference::flow)
          .collect(Collectors.joining(", ", "[", "]"));
    }
    if (value.isMap()) {
      return value.childrenMap().entrySet().stream()
          .map(e -> e.getKey() + ": " + flow(e.getValue()))
          .collect(Collectors.joining(", ", "{ ", " }"));
    }
    return scalar(value.raw());
  }

  private static String scalar(@Nullable Object raw) {
    if (raw == null) {
      return "\"\"";
    }
    if (raw instanceof Number || raw instanceof Boolean) {
      return raw.toString();
    }
    String text = raw.toString();
    if (PLAIN.matcher(text).matches()
        && !RESERVED.contains(text.toLowerCase(Locale.ROOT))
        && !text.matches("[0-9.+-]+")) {
      return text;
    }
    return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
  }

  /** Writes a comment wrapped to the page width, a {@code #} at the start of each line. */
  private static void comment(StringBuilder out, String indent, String text) {
    String prefix = indent + "# ";
    StringBuilder line = new StringBuilder(prefix);
    for (String word : text.split(" ", -1)) {
      if (line.length() > prefix.length() && line.length() + 1 + word.length() > WIDTH) {
        out.append(line).append('\n');
        line = new StringBuilder(prefix);
      }
      if (line.length() > prefix.length()) {
        line.append(' ');
      }
      line.append(word);
    }
    out.append(line).append('\n');
  }

  // ----- docs/config.md -----

  /** {@code docs/config.md}: each file's settings with their defaults and descriptions. */
  public static String markdown() {
    StringBuilder out = new StringBuilder();
    out.append("# Configuration reference\n\n")
        .append(
            "Generated from the code by `./gradlew :core:configReference`; don't edit it by hand."
                + " Each setting's description is its `@Comment` in"
                + " `core/src/main/java/com/petepeterepeat/seasons/core/config/`, and the same"
                + " text is written as comments into the bundled `config.yml` and"
                + " `secrets.template.yml`.\n\n")
        .append(
            "Seasons keeps human-owned settings in `plugins/Seasons/`. Edit a file, then run"
                + " `/season reload`: every file is checked first, and on any error the old"
                + " settings stay in force and the error (file, line, message) goes to the console"
                + " and online admins. `/season config get|set` reads and changes `config.yml`"
                + " in game. A file only needs the keys it changes; everything else takes the"
                + " default below. Durations are written like `7d`, `1h30m`, `10m` or `10s`."
                + " Command-edited files (each season's file, galleries, `hall.yml`,"
                + " `museum.yml`) are written by the `/season` and `/hof` commands and aren't"
                + " listed here.\n\n");
    out.append("## `config.yml`\n\n");
    sections(out, SeasonsConfig.class, bundled("config.yml"));
    out.append("## `secrets.yml`\n\n")
        .append(
            "Webhook URLs, kept apart so `config.yml` can be shared. The environment variables"
                + " `SEASONS_DISCORD_WEBHOOK` and `SEASONS_ADMIN_WEBHOOK` override them. URLs must"
                + " use https; an empty value turns a webhook off. Never logged or echoed.\n\n");
    table(out, Secrets.class, "", bundled("secrets.template.yml"));
    return out.toString();
  }

  private static void sections(StringBuilder out, Class<?> type, ConfigurationNode values) {
    for (RecordComponent component : type.getRecordComponents()) {
      String key = component.getName();
      out.append("### `").append(key).append("`\n\n");
      out.append(describe(component)).append("\n\n");
      Class<?> nested = section(component);
      if (nested != null) {
        table(out, nested, key + ".", values.node(key));
      } else {
        out.append("| Key | Default |\n| --- | --- |\n");
        for (Map.Entry<Object, ? extends ConfigurationNode> child :
            values.node(key).childrenMap().entrySet()) {
          out.append("| `")
              .append(key)
              .append('.')
              .append(child.getKey())
              .append("` | `")
              .append(flow(child.getValue()))
              .append("` |\n");
        }
        out.append('\n');
        Class<?> entries = mapValues(component);
        if (entries != null) {
          out.append("Each entry:\n\n");
          for (RecordComponent part : entries.getRecordComponents()) {
            out.append("- `")
                .append(part.getName())
                .append("`: ")
                .append(describe(part))
                .append('\n');
          }
          out.append('\n');
        }
      }
    }
  }

  private static void table(
      StringBuilder out, Class<?> type, String prefix, ConfigurationNode values) {
    out.append("| Key | Default | Description |\n| --- | --- | --- |\n");
    rows(out, type, prefix, values);
    out.append('\n');
  }

  private static void rows(
      StringBuilder out, Class<?> type, String prefix, ConfigurationNode values) {
    for (RecordComponent component : type.getRecordComponents()) {
      String path = prefix + component.getName();
      ConfigurationNode value = values.node(component.getName());
      Class<?> nested = section(component);
      if (nested != null) {
        out.append("| `").append(path).append("` | | ").append(cell(describe(component)));
        out.append(" |\n");
        rows(out, nested, path + ".", value);
      } else {
        out.append("| `")
            .append(path)
            .append("` | `")
            .append(flow(value))
            .append("` | ")
            .append(cell(describe(component)))
            .append(" |\n");
      }
    }
  }

  private static String cell(String text) {
    return text.replace("|", "\\|");
  }

  // ----- the records -----

  /** A component's comment, then the values an enum takes. */
  private static String describe(RecordComponent component) {
    String text = comment(component);
    String described = text == null ? "" : text;
    Class<?> type = component.getType();
    if (type.isEnum()) {
      described +=
          Arrays.stream(type.getEnumConstants())
              .map(c -> "`" + ((Enum<?>) c).name().toLowerCase(Locale.ROOT).replace('_', '-') + "`")
              .collect(Collectors.joining(", ", " One of ", "."));
    }
    return described;
  }

  private static @Nullable String comment(RecordComponent component) {
    try {
      Comment comment =
          component
              .getDeclaringRecord()
              .getDeclaredField(component.getName())
              .getAnnotation(Comment.class);
      return comment == null ? null : comment.value();
    } catch (NoSuchFieldException e) {
      return null;
    }
  }

  /** The record a component holds, for a section of settings; null for a plain value. */
  private static @Nullable Class<?> section(RecordComponent component) {
    Class<?> type = component.getType();
    return type.isRecord() ? type : null;
  }

  /** The record a map component holds as its values, such as each award; null otherwise. */
  private static @Nullable Class<?> mapValues(RecordComponent component) {
    if (component.getGenericType() instanceof java.lang.reflect.ParameterizedType map
        && map.getRawType() == Map.class
        && map.getActualTypeArguments()[1] instanceof Class<?> value
        && value.isRecord()) {
      return value;
    }
    return null;
  }

  private static ConfigurationNode bundled(String name) {
    InputStream in = ConfigReference.class.getResourceAsStream(RESOURCES + name);
    if (in == null) {
      throw new IllegalStateException("No bundled " + name);
    }
    try {
      return YamlConfigurationLoader.builder()
          .source(() -> new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
          .build()
          .load();
    } catch (ConfigurateException e) {
      throw new UncheckedIOException(new IOException("Can't read the bundled " + name, e));
    }
  }
}
