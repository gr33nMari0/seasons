---
title: Links
description: Buttons, signs, plates, doorways and floating text that take players to the Hall, any season's spawn, a named point or another link, and who may take them.
art: map
audience: admins
since: 1.3.0
sidebar:
  order: 8
---

A **link** is one destination with any number of **triggers**: blocks players click or stand on, areas they walk or fly through, and floating text they click. Seasons takes over the click, so a button sends no redstone and no command blocks are needed.

## Making a link

Look at a block and run:

```text
/hof link add to-hall from looked to hall
```

That's the whole grammar: `/hof link add <id> [from <source>] to <destination>`, with options:

| Option | Does |
| --- | --- |
| `--trigger use\|step\|both\|area` | How a block fires: clicked (`use`, the default), stepped on (`step`, the default for pressure plates and tripwire), either (`both`), or a box players pass through (`area`) |
| `--two-way` | Also makes `<id>-back`, a link from the destination back to this one's landing |
| `--name <text>` | The name players read, in MiniMessage colours |

Or with the wand: right-click any block, anywhere in any world, and choose **Make a link** from the menu. The wand also remembers a block you set aside for the next `/hof link add`.

Standing on a button or a sign never takes a link; only clicking does. Put `both` on a block meant to be walked onto: `/hof link trigger kind <id> 1 both`.

## Destinations

| Destination | Goes to |
| --- | --- |
| `hall` | The Hall spawn |
| `season <n>` | That season's spawn. A past season's is recorded with `/season spawn record <n>` |
| `point <name>` | A named spot or box, made with `/hof point add <name>` |
| `link <id>` | Another link's landing, so two links make a pair |
| `gallery <id>` | A gallery's entry |
| `return` | Back where the player came from, as `/hof leave` |
| a spot | `/hof link to <id>` sets it where you stand |

A link to the Hall skips `/hof`'s bed check; the other entry checks apply. With `hof.links.returnToLink: true` (the default), `/hof leave` brings the player back to the link they came from.

## Triggers

| Kind | Make it with |
| --- | --- |
| A block | `from looked`, `from here`, `from x y z`, or the wand |
| An area, up to 32 blocks a side (a doorway, a frame in the sky; elytra flights count) | `from sel` with `--trigger area`, or two corners |
| A link sign | `/hof link item <id> [count] [player]` gives signs; each becomes a trigger where it's placed |
| Floating text | `/hof link text add <id> <x y z> <text>` |

Add more triggers to a link with `/hof link from <id> <source>` (or `/hof link trigger add`), list them with `/hof link trigger list <id>`, and remove one with `/hof link trigger remove <id> <n>`.

### Signs and floating text

- `/hof link sign <id> line <1-4> <text>` writes a sign line; `/hof link sign <id> glow on|off` makes it glow without ink.
- `/hof link text line <id> <n> <line> <text>` writes floating text; `/hof link text scale <id> <n> <size>` resizes it (0.25 to 4); `/hof link text edit <id> <n>` sets how it's taken (`--walk r` to walk into it, `--left` to left-click) and how it turns (`--billboard center|vertical|fixed`).
- `/hof link name <id> <text>` sets the name players read for a link, in MiniMessage colours.
- Text takes [MiniMessage](/docs/reference/messages/) colours and decorations. Click and hover tags are stripped (`links.styleTags`).

## Who may take a link, and when

`/hof link access <id> everyone|op|permission [node]`:

- **everyone**: links to the Hall and to season spawns, by default.
- **op**: every other link, by default (`links.access`).
- **permission**: holders of `seasons.link.use.<id>`, the wildcard `seasons.link.use.*`, or any node you name, such as a LuckPerms group's.

`/hof link when <id> <condition>`:

| Condition | The link works |
| --- | --- |
| `always` | Always (the default) |
| `after-homecoming` | Once the old world is open |
| `stage frontier` / `stage open` | Only in that stage of the season |
| `played <n>` | Only for players who played season `n` |

**Locked land always wins.** A link into land that's locked for that player is refused with the reason, such as "That leads into land locked until Homecoming". Links out of the Hall are refused during the Intermission, except for holders of `seasons.hof.roam`.

## The link editor

As a link admin (`seasons.admin.links`), **sneak-right-click** a trigger, or click it with the wand, to open its editor: where it leads, its name, who may take it, test it, remove it. It's a dialog on current clients, or a chest menu (`links.editor.ui: menu`).

## Pairs, landings and points

- `/hof link pair <id> <other>` makes two links lead to each other; `/hof link unpair <id>` makes one one-way again.
- `/hof link landing <id>` sets where players arrive when a link leads to this one.
- **Points** are named spots or boxes for links to start or end at: `/hof point add <name>` where you stand, or a box of two corners. `/hof point list` and `/hof point info <name>` show them, `/hof point move <name>` and `/hof point rename <name> <new>` change them (links follow), `/hof point remove <name>` removes one, and `/hof point tp <name>` goes there.

## Command blocks and admins

- `/hof link send <targets> <link>` sends players along a link from a command block or as an admin: `@p`, `@a[distance=..5]`, a name. `--checks none|entry|full` picks which checks apply (`links.commandBlocks.checks`, `entry` by default). Only players are moved.
- `/hof link go <id>` takes a link while you stand within 6 blocks of one of its triggers. Link admins can take any link from anywhere.

## Managing links

`/hof link list [near|world|to <destination>]`, `/hof link info <id>`, `/hof link rename <id> <new>` (links leading to it follow), `/hof link move <id>` and `/hof link remove <id>`. Every command is in the [command reference](/docs/reference/commands/?q=hof+link).

Links live in `<world>/seasons/links.yml`. Upgrading from 1.2? See [Upgrading](/docs/start/upgrading/#from-12-to-13).
