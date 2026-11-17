// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import com.petepeterepeat.seasons.api.SeasonsApi;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.Platform;
import java.util.OptionalInt;
import java.util.Set;

/** The Seasons core. An adapter builds its {@link Platform} and starts one of these. */
public final class Seasons implements SeasonsApi {
  /** Permission for season admin commands such as {@code /season status}. */
  public static final String PERMISSION_ADMIN_SEASON = "seasons.admin.season";

  private final Platform platform;
  private final String version;
  private final Messages messages;

  public Seasons(Platform platform, String version, Messages messages) {
    this.platform = platform;
    this.version = version;
    this.messages = messages;
  }

  /** Registers commands and starts deciding intents. */
  public void start() {
    platform.events().handle(intent -> Decision.allow());
    platform.commands().register(seasonCommand());
  }

  @Override
  public String version() {
    return version;
  }

  @Override
  public OptionalInt currentSeason() {
    return OptionalInt.empty();
  }

  CommandNode seasonCommand() {
    return CommandNode.literal("season")
        .then(
            CommandNode.literal("status")
                .permission(PERMISSION_ADMIN_SEASON)
                .executes(this::status))
        .build();
  }

  private void status(CommandSource source) {
    String name = platform.name();
    source.reply(messages.get("status.header", "version", version, "platform", name));
    OptionalInt season = currentSeason();
    if (season.isPresent()) {
      source.reply(messages.get("status.season", "season", String.valueOf(season.getAsInt())));
    } else {
      source.reply(messages.get("status.no-season"));
    }
    Set<Capability> gaps = platform.capabilities().gaps();
    if (gaps.isEmpty()) {
      source.reply(messages.get("status.capabilities-ok", "platform", name));
    }
    for (Capability gap : gaps) {
      source.reply(
          messages.get("status.capability-gap", "platform", name, "capability", gap.name()));
    }
  }
}
