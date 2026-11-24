---
title: Install
description: Requirements, installing Seasons and Seasons Web, and the files they write.
art: chest
audience: owners
sidebar:
  order: 1
---

Seasons is a server-side Paper plugin. Players join with an unmodified game and install nothing.

## Requirements

- **Paper 26.3**, or a fork that runs Paper plugins, such as Purpur. Spigot and Folia aren't supported.
- **Java 25.**
- Optional: **[BlueMap](https://bluemap.bluecolored.de/) 5.24 or newer**, to show spawns, zones, the Hall and shared waypoints on your web map.
- Optional: any permissions plugin, such as **LuckPerms**. Without one, player commands work for everyone and admin commands for ops.

## Install Seasons

1. Download `Seasons-<version>.jar` from the [download page](/download/), Modrinth, Hangar or GitHub Releases. Each release lists SHA-256 checksums in `SHA256SUMS`, so you can check the file.
2. Stop the server, put the jar in `plugins/`, and start it again.
3. Read the startup lines in the console. They say which version started, the season state, and anything that needs your attention:

   ```text
   [Seasons] Seasons 1.4.0 on Paper, Java 25
   [Seasons] Season: none recorded. Run /season init <season> to start.
   [Seasons] Ready in 380 ms. /season info for details.
   ```

Seasons sits idle until you record a season with `/season init`, so installing it partway through a season you are already playing is fine. Next: [your first season](/docs/start/first-season/).

## The files Seasons writes

| File | What it holds |
| --- | --- |
| `plugins/Seasons/config.yml` | Every setting, with a comment on each. See [Configuration](/docs/reference/config/). |
| `plugins/Seasons/messages.yml` | Every line players read, in [MiniMessage](/docs/reference/messages/). |
| `plugins/Seasons/secrets.yml` | Discord webhook URLs. Keep it private; never share or commit it. |
| `<world>/seasons/` | The season's state, the Hall's layout, galleries, links, archives, vaults and the journal. Back it up with the world. |

Edit a file, then run `/season reload`. Every file is checked first: on any error, the old settings stay in force and the error (file, line and what's wrong) goes to the console and to online admins. `/season validate` checks the files without reloading. `/season config get` and `/season config set` read and change `config.yml` in game.

## Discord

Seasons posts reminders, the season's end, awards, firsts, the dragon slayer, the launch and Homecoming to Discord through webhooks, with no bot. Put the URLs in `secrets.yml`:

```yaml
discordWebhook: "https://discord.com/api/webhooks/…"        # public posts
adminWebhook: "https://discord.com/api/webhooks/…"          # alerts for admins
```

Or set the environment variables `SEASONS_DISCORD_WEBHOOK` and `SEASONS_ADMIN_WEBHOOK`. Webhook URLs are never written to the log in full.

## Seasons Web (optional)

For the live dashboard, also put `SeasonsWeb-<version>.jar` in `plugins/`. It needs Seasons and does nothing until you turn it on. See [Seasons Web](/docs/owners/seasons-web/).

## Metrics

Seasons counts how many servers run it with [bStats](https://bstats.org/plugin/bukkit/SeasonsMC/35499): what bStats always sends (server software, game and Java versions, player count) plus four anonymous charts (whether Seasons Web is installed, the cell assignment mode, the cell access mode, how many seasons the server has run). It never sends names, coordinates or addresses. To turn bStats off for every plugin, set `enabled: false` in `plugins/bStats/config.yml`.
