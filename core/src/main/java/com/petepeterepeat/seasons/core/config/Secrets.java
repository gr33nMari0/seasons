// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import java.net.URI;
import java.util.Optional;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/**
 * {@code plugins/Seasons/secrets.yml}, overridden by the environment variables {@code
 * SEASONS_DISCORD_WEBHOOK} and {@code SEASONS_ADMIN_WEBHOOK}. Never logged or echoed: {@link
 * #toString} redacts.
 *
 * @param discordWebhook the public Discord channel's webhook
 * @param adminWebhook the admins' webhook for alerts
 */
public record Secrets(
    @Comment("Discord webhook for reminders and announcements.") Optional<URI> discordWebhook,
    @Comment("Discord webhook for admin alerts (tripwire hits, recovery runs, reconcile repairs).")
        Optional<URI> adminWebhook) {
  public static Secrets none() {
    return new Secrets(Optional.empty(), Optional.empty());
  }

  @Override
  public String toString() {
    return "Secrets[discordWebhook="
        + (discordWebhook.isPresent() ? "<set>" : "<none>")
        + ", adminWebhook="
        + (adminWebhook.isPresent() ? "<set>" : "<none>")
        + "]";
  }
}
