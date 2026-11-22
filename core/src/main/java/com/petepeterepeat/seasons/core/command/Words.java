// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.command;

import java.util.List;
import java.util.regex.Pattern;

/** The words of typed text: split on runs of whitespace, with no empty words. */
public final class Words {
  private static final Pattern SPACE = Pattern.compile("\\s+");

  private Words() {}

  public static List<String> of(String text) {
    return SPACE.splitAsStream(text.trim()).filter(w -> !w.isEmpty()).toList();
  }
}
