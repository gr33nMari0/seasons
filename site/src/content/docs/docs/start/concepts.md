---
title: How Seasons works
description: The four scopes, the phases of a season and the words these docs use.
art: tree
audience: everyone
sidebar:
  order: 3
---

Seasons runs a season format on one world that never resets. Every rule follows from four scopes: **what you carry becomes a trophy, the old world waits for Homecoming, one-offs live in the museum, and everything else is open frontier from day one.**

## The four scopes

| Scope | What's in it | During a season | How |
| --- | --- | --- | --- |
| **Season gear** | Everything on a player at the end of the season, plus their advancements | On display in their Hall of Fame cell, view-only, forever | The archive |
| **The old world** | Bases, farms, megaprojects and their storage; ender chest contents | Locked until Homecoming: no entering, building or taking. Then everything opens again | [Zones](/docs/owners/zones/) and the [ender vault](/docs/owners/vault/) |
| **Server collectibles** | The original dragon egg, each season's egg, awards, one-off items | Display-only, forever | The [museum](/docs/owners/museum/) |
| **The frontier** | Everywhere outside the old world, the new spawn included | Open from launch, with no distance limit | Nothing to set up |

## A season, start to finish

1. **Frontier.** Everyone starts at a fresh spawn far out in unexplored land. The old world is locked and ender chests are sealed.
2. **Open.** When the group votes for it, an admin runs `/season unlock homecoming`. The whole world opens: old bases, chests and items are playable again.
3. **Ending.** The group votes to end; an admin sets a date at least a week out. Reminders count down to it. At a live event, `/season end` starts a countdown, then archives everyone online into their cell.
4. **Intermission.** Everyone wakes up in the Hall. Awards and season firsts are announced. Anyone who was offline is archived when they next log in.
5. **Launch.** `/season launch` sends everyone, with their registered pets, to the new spawn. The next season begins in its Frontier stage.

Nothing happens on its own: an admin ends and launches each season. A server that wants a hard stop can set `schedule.autoEnd: true`.

## Words these docs use

| Word | Meaning |
| --- | --- |
| **Season** | A numbered play period, such as Season 3. One is active at a time. |
| **Phase** | Where the server is: Active, Ending or Intermission. Launch is the instant switch back to Active. |
| **Stage** | Within Active: Frontier (old world locked), then Open (after Homecoming). |
| **Planned end** | The time reminders count down to. The season ends when an admin runs `/season end`. |
| **Homecoming** | The moment the old world opens for everyone: vaults unseal and every zone switches to its after-Homecoming mode. |
| **Rollover** | The end, then the Intermission, then the launch. |
| **Archive** | The stored record of a player's gear, advancements and stats for one season. It can never be withdrawn. |
| **Display** | What the world shows of an archive: figure, trophies, plaque, archive view. Rebuilt from the archive at any time. |
| **Hall** | The protected Hall of Fame: the hub plus every gallery. |
| **Hub** | The Hall's shared centre, one box. It holds the Hall spawn, and galleries lead off it. |
| **Hall spawn** | The spot in the hub where plain `/hof` lands. |
| **Gallery** | The part of the Hall for one season: a wing, a room or a row of plinths. |
| **Cell** | One player's slot in a gallery. |
| **Anchor** | A marked spot in a cell: figure, archive, plaque, trophy, entry or gate. See [Anchors](/docs/owners/anchors/). |
| **Link** | Something players click, step on or walk through to go somewhere: the Hall, a season's spawn, a named point. See [Links](/docs/owners/links/). |
| **Zone** | A named area with a mode (off, announce, block or no-build), an unlock condition, and a mode for after it unlocks. |
| **Ender vault** | A sealed copy of a player's ender chest from the end of the season. Withdraw-only after Homecoming. |
| **Museum** | A gallery with no owners, for one-off items and each season's dragon egg. |
| **Journal** | An append-only log written before any item moves, so a crash never loses or duplicates one. |
