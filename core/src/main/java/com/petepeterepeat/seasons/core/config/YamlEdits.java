// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * Changes one value in a YAML file people also edit by hand, such as config.yml, by changing only
 * that value's characters: every comment, blank line and other value stays as it was. A key the
 * file lacks is added under its section, and missing sections are made. Uses SnakeYAML's composer,
 * which knows where each node starts and ends (docs/adr/0005-snakeyaml-for-config-set.md).
 */
public final class YamlEdits {
  private static final int INDENT = 2;

  private YamlEdits() {}

  /** Why an edit can't be made by command, as admins read it. */
  public static class Refused extends Exception {
    private static final long serialVersionUID = 1L;

    public Refused(String message) {
      super(message);
    }
  }

  /** The file doesn't parse, at a line and column counted from 1. */
  public static final class Unreadable extends Refused {
    private static final long serialVersionUID = 1L;
    private final int line;
    private final int column;

    public Unreadable(int line, int column, String message) {
      super(message);
      this.line = line;
      this.column = column;
    }

    public int line() {
      return line;
    }

    public int column() {
      return column;
    }
  }

  /**
   * A value an admin typed, as YAML writes it on one line: {@code 30s}, {@code true}, {@code [2d,
   * 1h]}, quoted only where YAML would otherwise read it as something else.
   */
  public static String value(String typed) throws Refused {
    Object value;
    try {
      value = new Yaml(new SafeConstructor(new LoaderOptions())).load(typed);
    } catch (YAMLException e) {
      throw new Refused("'" + typed + "' isn't a value YAML reads");
    }
    if (value == null) {
      throw new Refused("it needs a value");
    }
    if (value instanceof Map<?, ?>) {
      throw new Refused("set one key at a time, not a section");
    }
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.FLOW);
    options.setWidth(Integer.MAX_VALUE);
    options.setSplitLines(false);
    return new Yaml(options).dump(value).strip();
  }

  /**
   * The file's text with the key at {@code path} set to {@code value}, which is YAML text on one
   * line, such as {@link #value} gives.
   */
  public static String set(String text, List<String> path, String value) throws Refused {
    List<Node> documents = new ArrayList<>();
    try {
      new Yaml().composeAll(new StringReader(text)).forEach(documents::add);
    } catch (MarkedYAMLException e) {
      Mark mark = e.getProblemMark() != null ? e.getProblemMark() : e.getContextMark();
      throw new Unreadable(
          mark == null ? 0 : mark.getLine() + 1,
          mark == null ? 0 : mark.getColumn() + 1,
          String.valueOf(e.getProblem()));
    } catch (YAMLException e) {
      throw new Unreadable(0, 0, firstLine(e.getMessage()));
    }
    if (documents.size() > 1) {
      throw new Refused("the file holds more than one YAML document; change it by hand");
    }
    String newline = text.contains("\r\n") ? "\r\n" : "\n";
    if (documents.isEmpty()) {
      String start = text.isBlank() ? "schema-version: 1" + newline : text;
      if (!start.isEmpty() && !start.endsWith("\n")) {
        start += newline;
      }
      return start + block(path, 0, value, newline);
    }
    if (!(documents.getFirst() instanceof MappingNode root)) {
      throw new Refused("the file isn't a map of settings");
    }
    MappingNode at = root;
    for (int i = 0; i < path.size(); i++) {
      plain(at);
      NodeTuple found = find(at, path.get(i));
      if (found == null) {
        return insert(text, at, path.subList(i, path.size()), value, newline);
      }
      Node node = found.getValueNode();
      plain(node);
      if (i == path.size() - 1) {
        if (node instanceof MappingNode) {
          throw new Refused(String.join(".", path) + " is a section");
        }
        return replace(text, found, value);
      }
      if (!(node instanceof MappingNode section)) {
        throw new Refused(String.join(".", path.subList(0, i + 1)) + " isn't a section");
      }
      at = section;
    }
    throw new IllegalArgumentException("A path needs at least one key");
  }

  /** Refuses what can't be edited in place: anchors, aliases and merge keys. */
  private static void plain(Node node) throws Refused {
    if (node.getAnchor() != null) {
      throw new Refused("the file uses a YAML anchor or alias here; change it by hand");
    }
    if (node instanceof MappingNode mapping) {
      for (NodeTuple tuple : mapping.getValue()) {
        if (tuple.getKeyNode() instanceof ScalarNode key && key.getValue().equals("<<")) {
          throw new Refused("the file uses a YAML merge key here; change it by hand");
        }
      }
    }
  }

  private static @Nullable NodeTuple find(MappingNode mapping, String key) {
    for (NodeTuple tuple : mapping.getValue()) {
      if (tuple.getKeyNode() instanceof ScalarNode scalar && scalar.getValue().equals(key)) {
        return tuple;
      }
    }
    return null;
  }

  /** Replaces a key's value, keeping everything around it. */
  private static String replace(String text, NodeTuple tuple, String value) {
    Node node = tuple.getValueNode();
    int keyEnd = index(text, tuple.getKeyNode().getEndMark());
    int colon = text.indexOf(':', keyEnd);
    int start = index(text, node.getStartMark());
    int end = end(text, node);
    boolean empty =
        node instanceof ScalarNode scalar && scalar.getValue().isEmpty() && start >= end;
    if (empty || text.lastIndexOf('\n', start) > colon) {
      // A value on the lines below its key, such as a block list, or none at all: the new one goes
      // on the key's line.
      return text.substring(0, colon + 1) + " " + value + text.substring(empty ? colon + 1 : end);
    }
    return text.substring(0, start) + value + text.substring(end);
  }

  /** Adds the rest of a path, the first key of which a mapping lacks, with its value. */
  private static String insert(
      String text, MappingNode mapping, List<String> rest, String value, String newline) {
    List<NodeTuple> tuples = mapping.getValue();
    if (mapping.getFlowStyle() == DumperOptions.FlowStyle.FLOW) {
      String entry = flow(rest, value);
      if (tuples.isEmpty()) {
        int closing = index(text, mapping.getEndMark()) - 1;
        return text.substring(0, closing) + entry + text.substring(closing);
      }
      int after = end(text, tuples.getLast().getValueNode());
      return text.substring(0, after) + ", " + entry + text.substring(after);
    }
    int indent = tuples.getFirst().getKeyNode().getStartMark().getColumn();
    int last = end(text, tuples.getLast().getValueNode());
    int lineEnd = text.indexOf('\n', last);
    String lines = block(rest, indent, value, newline);
    if (lineEnd < 0) {
      return text + newline + lines;
    }
    return text.substring(0, lineEnd + 1) + lines + text.substring(lineEnd + 1);
  }

  /** {@code a: { b: value }}. */
  private static String flow(List<String> path, String value) {
    return path.size() == 1
        ? path.getFirst() + ": " + value
        : path.getFirst() + ": { " + flow(path.subList(1, path.size()), value) + " }";
  }

  /** {@code a:\n b: value\n}, starting at {@code indent}. */
  private static String block(List<String> path, int indent, String value, String newline) {
    String key = " ".repeat(indent) + path.getFirst() + ":";
    return path.size() == 1
        ? key + " " + value + newline
        : key + newline + block(path.subList(1, path.size()), indent + INDENT, value, newline);
  }

  /**
   * Where a node's own text ends: a block mapping or list ends with its last item, not where the
   * next key starts, so comments after it stay.
   */
  private static int end(String text, Node node) {
    if (node instanceof MappingNode mapping
        && mapping.getFlowStyle() != DumperOptions.FlowStyle.FLOW
        && !mapping.getValue().isEmpty()) {
      return end(text, mapping.getValue().getLast().getValueNode());
    }
    if (node instanceof SequenceNode sequence
        && sequence.getFlowStyle() != DumperOptions.FlowStyle.FLOW
        && !sequence.getValue().isEmpty()) {
      return end(text, sequence.getValue().getLast());
    }
    return index(text, node.getEndMark());
  }

  /** A mark's place in the text: SnakeYAML counts code points, Java strings count chars. */
  private static int index(String text, Mark mark) {
    return text.offsetByCodePoints(
        0, Math.min(mark.getIndex(), text.codePointCount(0, text.length())));
  }

  private static String firstLine(@Nullable String message) {
    return message == null ? "?" : message.lines().findFirst().orElse("?");
  }
}
