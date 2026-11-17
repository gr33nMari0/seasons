// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.command;

/** What a command argument accepts. Each adapter maps these onto its own argument types. */
public sealed interface ArgumentType {
  /** A whole number from {@code min} to {@code max}. */
  record IntegerArgument(int min, int max) implements ArgumentType {}

  /** One word with no spaces. */
  record WordArgument() implements ArgumentType {}

  /** Everything to the end of the command, such as a time or a list of flags. */
  record GreedyArgument() implements ArgumentType {}

  static ArgumentType integer(int min, int max) {
    return new IntegerArgument(min, max);
  }

  static ArgumentType word() {
    return new WordArgument();
  }

  static ArgumentType greedy() {
    return new GreedyArgument();
  }
}
