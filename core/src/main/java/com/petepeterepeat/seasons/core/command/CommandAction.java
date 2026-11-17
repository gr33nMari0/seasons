// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.command;

/** What a command does when it runs. */
@FunctionalInterface
public interface CommandAction {
  void run(CommandSource source, Arguments arguments);
}
