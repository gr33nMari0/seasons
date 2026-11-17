// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import java.util.Optional;

/**
 * One error in one file, as reported to the console and to admins.
 *
 * @param file the file's name relative to its folder, such as {@code config.yml}
 * @param line 1-based, for syntax errors
 * @param path the key path, such as {@code schedule.endCountdown}, for invalid values
 */
public record ConfigProblem(
    String file, Optional<Position> line, Optional<String> path, String message) {
  /** A line and column in a file, both 1-based. */
  public record Position(int line, int column) {}

  public static ConfigProblem at(String file, int line, int column, String message) {
    return new ConfigProblem(
        file, Optional.of(new Position(line, column)), Optional.empty(), message);
  }

  public static ConfigProblem atPath(String file, String path, String message) {
    return new ConfigProblem(file, Optional.empty(), Optional.of(path), message);
  }

  public static ConfigProblem inFile(String file, String message) {
    return new ConfigProblem(file, Optional.empty(), Optional.empty(), message);
  }
}
