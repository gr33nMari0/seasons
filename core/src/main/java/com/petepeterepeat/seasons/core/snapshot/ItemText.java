// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.snapshot;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.petepeterepeat.seasons.api.ArchiveView;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.store.nbt.Nbt;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * An archived item described as text from its vanilla item data: id, count, custom name,
 * enchantments and trim. Plain Java on the neutral bytes, so it runs on any thread and never makes
 * a real item (docs/questions.md 297).
 */
public final class ItemText {
  private static final int MAX_NAME = 128;

  private ItemText() {}

  /** The item as text; empty if its data can't be read. */
  public static Optional<ArchiveView.ItemView> describe(String section, NeutralItem item) {
    Map<String, Object> root;
    try {
      root = Nbt.read(item.nbt());
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
    if (!(root.get("id") instanceof String id)) {
      return Optional.empty();
    }
    int count = root.get("count") instanceof Number n ? n.intValue() : 1;
    Map<String, Object> components = compound(root.get("components"));
    Optional<String> name =
        Optional.ofNullable(components.get("minecraft:custom_name"))
            .map(ItemText::text)
            .filter(t -> !t.isBlank())
            .map(t -> t.length() > MAX_NAME ? t.substring(0, MAX_NAME) : t);
    List<ArchiveView.Enchantment> enchantments = new ArrayList<>();
    enchantments(components.get("minecraft:enchantments"), enchantments);
    enchantments(components.get("minecraft:stored_enchantments"), enchantments);
    Map<String, Object> trim = compound(components.get("minecraft:trim"));
    Optional<ArchiveView.Trim> trimmed =
        trim.get("pattern") instanceof String pattern
                && trim.get("material") instanceof String material
            ? Optional.of(new ArchiveView.Trim(pattern, material))
            : Optional.empty();
    return Optional.of(new ArchiveView.ItemView(section, id, count, name, enchantments, trimmed));
  }

  private static void enchantments(@Nullable Object value, List<ArchiveView.Enchantment> into) {
    Map<String, Object> map = compound(value);
    // Before 1.21.5 the levels sat under "levels".
    if (map.get("levels") instanceof Map<?, ?>) {
      map = compound(map.get("levels"));
    }
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      if (entry.getValue() instanceof Number level && entry.getKey().contains(":")) {
        into.add(new ArchiveView.Enchantment(entry.getKey(), level.intValue()));
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> compound(@Nullable Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }

  /**
   * A text component as plain text: a string, a compound with {@code text} and {@code extra}, a
   * list of either, or (before 1.21.5) JSON in a string. Never parsed as formatting.
   */
  static String text(Object component) {
    StringBuilder out = new StringBuilder();
    append(component, out, 0);
    return out.toString();
  }

  private static void append(Object component, StringBuilder out, int depth) {
    if (depth > 16 || out.length() > MAX_NAME) {
      return;
    }
    switch (component) {
      case String s when looksLikeJson(s) -> {
        try {
          appendJson(JsonParser.parseString(s), out, depth + 1);
        } catch (RuntimeException e) {
          out.append(s);
        }
      }
      case String s -> out.append(s);
      case Nbt.NbtList list -> list.values().forEach(v -> append(v, out, depth + 1));
      case Map<?, ?> map -> {
        if (map.get("text") instanceof String s) {
          out.append(s);
        }
        if (map.get("extra") instanceof Nbt.NbtList extra) {
          extra.values().forEach(v -> append(v, out, depth + 1));
        }
      }
      default -> {}
    }
  }

  private static boolean looksLikeJson(String s) {
    String t = s.strip();
    return (t.startsWith("{") && t.endsWith("}"))
        || (t.startsWith("[") && t.endsWith("]"))
        || (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\""));
  }

  private static void appendJson(JsonElement element, StringBuilder out, int depth) {
    if (depth > 16 || out.length() > MAX_NAME) {
      return;
    }
    if (element.isJsonPrimitive()) {
      out.append(element.getAsString());
    } else if (element.isJsonArray()) {
      element.getAsJsonArray().forEach(e -> appendJson(e, out, depth + 1));
    } else if (element.isJsonObject()) {
      var object = element.getAsJsonObject();
      if (object.has("text") && object.get("text").isJsonPrimitive()) {
        out.append(object.get("text").getAsString());
      }
      if (object.has("extra") && object.get("extra").isJsonArray()) {
        object.getAsJsonArray("extra").forEach(e -> appendJson(e, out, depth + 1));
      }
    }
  }
}
