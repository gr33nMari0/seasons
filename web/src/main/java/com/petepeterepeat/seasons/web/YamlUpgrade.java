// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds the settings a web.yml lacks, as {@code /web upgrade} does (M10 plan, O3): each one is
 * copied from the bundled web.yml with the comments that explain it, at the end of the file for a
 * whole section and at the end of its section for a key inside one, indented as that section is.
 * Every line the admin wrote stays as it was. It works on text rather than through a YAML library,
 * so it needs nothing beyond what Seasons Web already has: the bundled file's form (block style,
 * sections apart by blank lines) is known, and a section the admin wrote on one line, such as
 * {@code proxy: {trusted: []}}, is reported rather than rewritten.
 */
final class YamlUpgrade {
  private static final Pattern KEY = Pattern.compile("^( *)([A-Za-z0-9_-]+):(.*)$");

  /**
   * What an upgrade does.
   *
   * @param text the new file
   * @param added the paths added
   * @param byHand the paths it couldn't add, each with why
   */
  record Result(String text, List<String> added, List<String> byHand) {
    Result {
      added = List.copyOf(added);
      byHand = List.copyOf(byHand);
    }
  }

  private YamlUpgrade() {}

  /**
   * Adds {@code missing} (dotted paths, a whole section once) from {@code bundled} to {@code
   * written}.
   */
  static Result add(String written, String bundled, List<String> missing) {
    String newline = written.contains("\r\n") ? "\r\n" : "\n";
    List<String> lines = new ArrayList<>(List.of(written.replace("\r\n", "\n").split("\n", -1)));
    // split leaves one empty line after a final newline; the file is rebuilt with one at the end.
    if (!lines.isEmpty() && lines.getLast().isEmpty()) {
      lines.removeLast();
    }
    List<String> source = List.of(bundled.replace("\r\n", "\n").split("\n", -1));
    List<String> added = new ArrayList<>();
    List<String> byHand = new ArrayList<>();
    for (String path : missing) {
      List<String> parts = List.of(path.split("\\."));
      Optional<List<String>> block = block(source, parts);
      if (block.isEmpty()) {
        byHand.add(path + ": not in the bundled web.yml's usual form");
        continue;
      }
      List<String> taken = fill(path, block.get());
      if (parts.size() == 1) {
        while (!lines.isEmpty() && lines.getLast().isBlank()) {
          lines.removeLast();
        }
        if (!lines.isEmpty()) {
          lines.add("");
        }
        lines.addAll(taken);
        added.add(path);
        continue;
      }
      Optional<String> refused = insertNested(lines, parts, taken);
      if (refused.isPresent()) {
        byHand.add(path + ": " + refused.get());
      } else {
        added.add(path);
      }
    }
    return new Result(String.join(newline, lines) + newline, added, byHand);
  }

  /** Writes {@link SettingsAudit#PLACEHOLDER} for a setting only the admin can fill. */
  private static List<String> fill(String path, List<String> block) {
    if (!SettingsAudit.FILL_IN.contains(path)) {
      return block;
    }
    List<String> out = new ArrayList<>();
    for (String line : block) {
      Matcher m = KEY.matcher(line);
      if (m.matches() && !line.strip().startsWith("#") && path.endsWith(m.group(2))) {
        out.add(m.group(1) + m.group(2) + ": \"" + SettingsAudit.PLACEHOLDER + "\"");
      } else {
        out.add(line);
      }
    }
    return out;
  }

  /**
   * A setting's lines in the bundled file: the comments right above it, its own line, what is
   * indented under it, and commented-out examples right after it.
   */
  static Optional<List<String>> block(List<String> source, List<String> path) {
    int from = 0;
    int to = source.size();
    int indent = 0;
    int at = -1;
    for (String key : path) {
      at = find(source, from, to, indent, key);
      if (at < 0) {
        return Optional.empty();
      }
      int end = end(source, at, indent);
      from = at + 1;
      to = end;
      indent = childIndent(source, from, to).orElse(indent + 2);
    }
    int keyIndent = indentOf(source.get(at));
    int start = at;
    while (start > 0 && isComment(source.get(start - 1), keyIndent)) {
      start--;
    }
    int end = end(source, at, keyIndent);
    // Commented-out examples right under it, such as "#  - { label: … }".
    while (end < source.size() && isExample(source.get(end), keyIndent)) {
      end++;
    }
    return Optional.of(List.copyOf(source.subList(start, end)));
  }

  /** Adds a key's lines at the end of its section in the admin's file, indented as it is. */
  private static Optional<String> insertNested(List<String> lines, List<String> path, List<String> block) {
    int from = 0;
    int to = lines.size();
    int indent = 0;
    int at = -1;
    for (int i = 0; i < path.size() - 1; i++) {
      at = find(lines, from, to, indent, path.get(i));
      if (at < 0) {
        return Optional.of(path.get(i) + " isn't in the file as a section");
      }
      Matcher m = KEY.matcher(lines.get(at));
      String rest = m.matches() ? stripComment(m.group(3)).strip() : "";
      if (!rest.isEmpty()) {
        return Optional.of(path.get(i) + " is written on one line; add it there by hand");
      }
      int end = end(lines, at, indent);
      from = at + 1;
      to = end;
      indent = childIndent(lines, from, to).orElse(indent + 2);
    }
    int bundledIndent = block.stream().filter(l -> !l.isBlank()).mapToInt(YamlUpgrade::indentOf).min().orElse(0);
    List<String> shifted = new ArrayList<>();
    for (String line : block) {
      shifted.add(line.isBlank() ? line : " ".repeat(indent) + line.substring(Math.min(bundledIndent, indentOf(line))));
    }
    // After the section's last line that isn't blank.
    int insertAt = to;
    while (insertAt > from && lines.get(insertAt - 1).isBlank()) {
      insertAt--;
    }
    lines.addAll(insertAt, shifted);
    return Optional.empty();
  }

  /** The line of {@code key} at {@code indent} within lines [from, to), or -1. */
  private static int find(List<String> lines, int from, int to, int indent, String key) {
    for (int i = from; i < to; i++) {
      Matcher m = KEY.matcher(lines.get(i));
      if (m.matches() && m.group(1).length() == indent && m.group(2).equals(key)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Where a key's own lines end: after the lines indented deeper, and blank lines between them,
   * up to the next line at its indent or less.
   */
  private static int end(List<String> lines, int at, int indent) {
    int last = at;
    for (int i = at + 1; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.isBlank()) {
        continue;
      }
      if (indentOf(line) <= indent) {
        break;
      }
      last = i;
    }
    return last + 1;
  }

  private static Optional<Integer> childIndent(List<String> lines, int from, int to) {
    for (int i = from; i < to; i++) {
      String line = lines.get(i);
      if (!line.isBlank() && !line.strip().startsWith("#")) {
        return Optional.of(indentOf(line));
      }
    }
    return Optional.empty();
  }

  private static boolean isComment(String line, int indent) {
    return !line.isBlank() && indentOf(line) == indent && line.strip().startsWith("#");
  }

  private static boolean isExample(String line, int indent) {
    if (!isComment(line, indent)) {
      return false;
    }
    String after = line.strip().substring(1).strip();
    return after.startsWith("- ");
  }

  private static int indentOf(String line) {
    int i = 0;
    while (i < line.length() && line.charAt(i) == ' ') {
      i++;
    }
    return i;
  }

  /** A value with any trailing comment taken off, quotes respected. */
  private static String stripComment(String value) {
    boolean single = false;
    boolean dbl = false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\'' && !dbl) {
        single = !single;
      } else if (c == '"' && !single) {
        dbl = !dbl;
      } else if (c == '#' && !single && !dbl && (i == 0 || value.charAt(i - 1) == ' ')) {
        return value.substring(0, i);
      }
    }
    return value;
  }
}
