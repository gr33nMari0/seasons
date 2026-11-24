// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Link text (M8): sign lines, floating text and link names, written in MiniMessage. Only style tags
 * survive ({@code links.styleTags}: colours, gradients, rainbows, decorations, reset and shadow
 * colour); every other tag, click, hover, insertion, selector, score and NBT included, is stripped,
 * so text an admin types can never run a command or show someone else's data.
 */
public final class LinkText {
  private static final Pattern TAG = Pattern.compile("<(/?)(!?)([a-zA-Z_#][^<>]*)>");

  private static final Set<String> COLOURS =
      Set.of(
          "black",
          "dark_blue",
          "dark_green",
          "dark_aqua",
          "dark_red",
          "dark_purple",
          "gold",
          "gray",
          "grey",
          "dark_gray",
          "dark_grey",
          "blue",
          "green",
          "aqua",
          "red",
          "light_purple",
          "yellow",
          "white");

  private static final Set<String> DECORATIONS =
      Set.of(
          "bold",
          "b",
          "italic",
          "i",
          "em",
          "underlined",
          "u",
          "strikethrough",
          "st",
          "obfuscated",
          "obf");

  /** The most characters a line of link text keeps. */
  public static final int MAX_LINE = 120;

  private LinkText() {}

  /** The text with every tag but the allowed style tags taken out, and clamped in length. */
  public static String clean(String text, List<String> allowed) {
    Matcher matcher = TAG.matcher(text);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      String body = matcher.group(3);
      matcher.appendReplacement(
          out, allowed(body, allowed) ? Matcher.quoteReplacement(matcher.group()) : "");
    }
    matcher.appendTail(out);
    String cleaned = out.toString().replace("\n", " ");
    return cleaned.length() > MAX_LINE ? cleaned.substring(0, MAX_LINE) : cleaned;
  }

  /** Whether a tag's body, such as {@code gold} or {@code gradient:red:blue}, is allowed. */
  static boolean allowed(String body, List<String> allowed) {
    int colon = body.indexOf(':');
    String name = (colon < 0 ? body : body.substring(0, colon)).toLowerCase(Locale.ROOT).trim();
    if (allowed.contains("color")
        && (COLOURS.contains(name)
            || name.startsWith("#")
            || name.equals("color")
            || name.equals("colour")
            || name.equals("c"))) {
      return true;
    }
    if (allowed.contains("decorations") && DECORATIONS.contains(name)) {
      return true;
    }
    if (allowed.contains("shadow_color") && name.equals("shadow")) {
      return true;
    }
    return switch (name) {
      case "gradient" -> allowed.contains("gradient");
      case "rainbow" -> allowed.contains("rainbow");
      case "reset" -> allowed.contains("reset");
      default -> false;
    };
  }

  /** The text with every tag taken out, as plain words. */
  public static String plain(String text) {
    return TAG.matcher(text).replaceAll("");
  }
}
