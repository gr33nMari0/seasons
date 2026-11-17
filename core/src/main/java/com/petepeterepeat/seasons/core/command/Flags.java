// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.command;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Flags such as {@code --dry-run --now}, read from a greedy argument. */
public final class Flags {
  private static final Pattern WORDS = Pattern.compile("\\s+");

  private Flags() {}

  /** What was given: the flags, or the first word that isn't an allowed flag. */
  public sealed interface Parsed {
    record Ok(Set<String> flags) implements Parsed {
      public Ok {
        flags = Set.copyOf(flags);
      }

      public boolean has(String flag) {
        return flags.contains(flag);
      }
    }

    record Unknown(String word) implements Parsed {}
  }

  public static Parsed parse(Optional<String> text, Set<String> allowed) {
    Set<String> flags = new LinkedHashSet<>();
    for (String word : WORDS.splitAsStream(text.orElse("").trim()).toList()) {
      if (word.isEmpty()) {
        continue;
      }
      if (!allowed.contains(word)) {
        return new Parsed.Unknown(word);
      }
      flags.add(word);
    }
    return new Parsed.Ok(flags);
  }
}
