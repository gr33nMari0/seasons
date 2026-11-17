// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import com.petepeterepeat.seasons.core.command.CommandNode;

/** Registers the command trees the core describes. */
public interface Commands {
  /** Registers a root command. Call before the platform finishes enabling the plugin. */
  void register(CommandNode root);
}
