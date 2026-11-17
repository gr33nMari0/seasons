// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.command;

import java.util.Map;
import java.util.Optional;

/** The arguments a command was run with, by name. */
public record Arguments(Map<String, Object> values) {
  public static final Arguments NONE = new Arguments(Map.of());

  public Arguments {
    values = Map.copyOf(values);
  }

  /** An integer argument; it must be on the path that ran. */
  public int integer(String name) {
    return (Integer) require(name);
  }

  /** A word or greedy argument; it must be on the path that ran. */
  public String string(String name) {
    return (String) require(name);
  }

  /** A word or greedy argument that is only on some paths, such as optional flags. */
  public Optional<String> optionalString(String name) {
    return Optional.ofNullable((String) values.get(name));
  }

  private Object require(String name) {
    Object value = values.get(name);
    if (value == null) {
      throw new IllegalArgumentException("No argument '" + name + "'");
    }
    return value;
  }
}
