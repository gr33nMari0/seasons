---
title: Integrations
description: BlueMap, Discord, LuckPerms, chat bridges and client map mods, and what Seasons does with each.
art: map-large
audience: owners
sidebar:
  order: 14
---

Every integration is optional and loads only when its plugin or setting is present. Seasons runs fully without any of them.

| With | What Seasons does |
| --- | --- |
| **[BlueMap](https://bluemap.bluecolored.de/)** 5.24+ | Publishes a `Seasons` marker set: season spawns, spawn candidates before launch, the Hall, the museum, locked zones as areas (no-build areas after Homecoming) and shared waypoints. Markers are kept in Seasons' data and republished when BlueMap reloads. |
| **Shared waypoints** | `/season marker add <set> <name>` saves your position as a BlueMap point, such as the hub or a megaproject. `list`, `move` and `remove` manage them; `/season tp marker` goes to one. |
| **Discord** | Outbound webhooks, no bot: reminders, the end, awards, firsts, the dragon slayer, the launch, Homecoming, and (optionally) announce-mode zone entries. A second admin webhook gets alerts: tripwire hits, repairs, recovery runs. URLs go in `secrets.yml`. |
| **LuckPerms** or any permissions plugin | Normal Bukkit permission checks. See [Permissions](/docs/reference/permissions/). |
| **Chat bridges** (DiscordSRV and the like) | Nothing needed: Seasons' broadcasts already appear in their relay. |
| **Seasons Web** | The live dashboard. See [Seasons Web](/docs/owners/seasons-web/). |

## Players' maps

- BlueMap's own settings keep players private on the live map: `hide-vanished: true` and `hide-sneaking: true`. The BlueMapPlayerControl plugin lets each player hide with `/bmpc hide`.
- [Map Link](https://modrinth.com/project/maplink), a client mod, reads BlueMap's markers and areas into Xaero's maps, so spawns, zones and waypoints appear for anyone running it.
- Spawn candidates can be shown as small, separate BlueMap maps so the group can vote on terrain without revealing the whole new area. See [Picking a spawn](/docs/guides/spawn-picking/).

## Managing waypoints

`/season marker list [set]` lists the waypoints, or one set's. `/season marker move <set> <name>` moves one where you stand (or `--at x y z`), and `/season marker remove <set> <name>` removes it.
