// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import com.petepeterepeat.seasons.core.command.CommandNode;
import java.util.List;

/** Registers the command trees the core describes. */
public interface Commands {
  /** Registers a root command. Call before the platform finishes enabling the plugin. */
  default void register(CommandNode root) {
    register(root, List.of());
  }

  /**
   * Registers a root command that can also be typed as each of {@code aliases}, such as {@code
   * /halloffame} for {@code /hof}.
   */
  void register(CommandNode root, List<String> aliases);
}
