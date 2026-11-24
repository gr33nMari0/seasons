---
title: The season lifecycle
description: Frontier, Open, Ending, Intermission and Launch, what Seasons does in each, and what happens to players who were offline.
art: hourglass
audience: owners
sidebar:
  order: 1
---

A season runs **Frontier**, **Open**, **Ending**, **Intermission**, then **Launch** starts the next one. The server moves between them only on an admin's command. Players who were offline catch up on their next login, so nobody is skipped or archived twice.

## The phases

| Phase | What happens | Ends when |
| --- | --- | --- |
| **Active: Frontier** | Play from the new spawn anywhere outside the old world, which is locked; ender chests are sealed | An admin runs `/season unlock homecoming` |
| **Active: Open** | The whole world is unlocked. The decorating window opens 7 days before the planned end | An admin runs `/season end` |
| **Ending** | A boss-bar countdown (10 minutes by default; `--now` skips it), then every gallery locks and everyone online is archived and sent to their cell | The last online player is archived |
| **Intermission** | Everyone is in the Hall. Awards and season firsts are announced. Late joiners are archived on login | An admin runs `/season launch` |
| **Launch** (instant) | Title, sound and fireworks; everyone online goes to the new spawn in survival with their registered pets; a new deaths objective; the dragon can drop a new egg; old-world zones lock | It becomes Frontier at once |

`/season info` shows any player the phase, the stage, the planned end and their cell. `/season status` shows admins more: players archived and waiting, and the journal's health.

## Ending is manual

Seasons doesn't run votes or end seasons by itself. The usual rhythm:

1. The group decides to end the season (in Discord, a poll, a meeting).
2. An admin sets the planned end, at least a week out: `/season schedule end 2026-12-20T20:00+00:00`. `/season schedule show` and `/season schedule clear` read and clear it.
3. Reminders count down to it in chat and on Discord: 7 days, 1 day, 1 hour and 10 minutes before (`schedule.reminders`).
4. At the planned time, nothing happens on its own. The admin runs `/season end` at the live event, so nobody is cut off mid-adventure. `/season cancel` stops the countdown.

For a hard stop instead, set `schedule.autoEnd: true`: the countdown then starts by itself at the planned end, or 5 minutes after the next start if the server was down.

## Launch is manual too

`/season launch` (with `--dry-run` first) starts the next season. You can also give the season's gallery a **launch button**, an op-only block, with `/hof gallery launch <gallery>`. The break between end and launch lasts as long as you like: minutes at the same event, or days.

Before launching, set the next spawn with `/season spawn set` (see [Picking a spawn](/docs/guides/spawn-picking/)). `/season launch --dry-run` reports anything missing.

## The decorating window

Cells are given out when the decorating window opens: `decorating.opensBefore` (7 days by default) before the planned end, or at once with `/season decorate open`. Owners decorate their own cell in survival, with their own blocks.

- Players can swap cells by agreement: both run `/hof swap <player>` within 60 seconds.
- Admins can assign cells by hand with `/hof cell assign`, before or after the window opens.
- After archiving, owners get `decorating.afterArchive` (15 minutes by default) to arrange their cell around their new figure. Then the cell locks, or at `/season decorate close`, or at launch, whichever comes first.

## Players who were offline

| When they log in | What happens |
| --- | --- |
| During the Intermission, having played the finished season | Archived, then sent to their cell |
| After the launch, having played the finished season | Archived, then sent straight to the new spawn, with their registered pets |
| After missing whole seasons | Archived into the last season they played |
| For the first time ever | No archive; they spawn at the season spawn |
| Having logged out inside the Hall | They stay there; `/hof leave` still takes them back |

`/season players pending` lists who is still waiting to be archived. `/season archive <player>` archives one player now.

## What archiving does

For each player, once per season:

1. Closes menus and puts cursor and crafting-grid items back in the inventory.
2. Writes everything they carry, their advancements and the ender chest into Seasons' own files, journalled, so a crash can't lose or duplicate anything.
3. Resets them for the new season: inventory, ender chest, XP, effects, advancements and respawn point (each configurable in `resets`).
4. Draws their cell's displays from the record: a figure in their armour and skin, the archive chest, the plaque and season card.

`/season unarchive <player> <season>` puts an archive back on a player, for mistakes only. It needs an empty inventory unless `--force` is given, and is logged.
