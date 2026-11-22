// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.command;

import java.util.List;

/**
 * What tab completion offers for an argument (questions 266). It reads only loaded, immutable
 * state, so an adapter may ask on any thread.
 */
@FunctionalInterface
public interface Suggester {
  /**
   * Whole words that could be typed as the last word of {@code typed}; the adapter keeps those that
   * start with what is typed of it.
   *
   * @param typed the argument's text so far: one word, or for an argument that takes the rest of
   *     the line, every word typed of it, the last perhaps half typed
   */
  List<String> suggest(CommandSource source, String typed);
}
