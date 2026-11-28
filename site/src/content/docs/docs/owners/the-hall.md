---
title: The Hall of Fame
description: The hub, galleries and cells, how cells are given out, how visitors get in, decorating, and how Seasons protects it all.
art: trophy
audience: owners
sidebar:
  order: 5
---

The Hall is a **hub** with **galleries** leading off it; galleries hold **cells**, and cells hold typed [anchors](/docs/owners/anchors/). Seasons draws each player's displays at those anchors from their archive, so it works with any build: a hub with wings, a castle, a row of plinths in a field.

## Build it, then mark it

Build the Hall however you like, anywhere in the world. Then tell Seasons where things are, with the wand (`/hof wand`): sneak-click two corners to select a box, then run the command that uses it.

| Part | Command | Notes |
| --- | --- | --- |
| **Hub** | `/hof hall hub` | One box. It may not overlap a gallery. |
| **Hall spawn** | `/hof hall spawn` | Where plain `/hof` lands. Must be inside the hub. May be a small box, so a crowd spreads over its floor. |
| **Exits** | `/hof hall exit` | A block or area that sends visitors back where they came from. |
| **Gallery** | `/hof gallery create <id> <season>`, then `/hof gallery bounds <id>` | One per season: a wing, a room or a building. |
| **Gallery entry** | `/hof gallery entry <id>` | Where `/hof <season>` lands. |
| **Cell** | `/hof cell new <gallery>`, then two wand corners | One player's slot. Ids run `c1`, `c2`, … unless you name them. Select the space **inside** the cell, not its walls (below). |

`/hof hall check` reports anything that doesn't fit together: a gallery that doesn't touch the hub, odd-sized cells, a gallery without an entry, cells without a figure, links leading nowhere. After each setup step, Seasons suggests the next one in chat.

A gallery that doesn't touch the hub (a museum in another building, say) is allowed; `/hof hall check` warns, because walking across the gap means leaving the Hall. Connect it, or reach it by a [link](/docs/owners/links/).

### Select the inside of a cell, not its walls

A cell's bounds are the space inside it: the air above its floor, up to its ceiling, between its walls. Owners may build and break anything in their bounds while decorating, so bounds selected from the outside let them break through the walls, floor and roof. Blocks they place against a wall still land inside, so selecting the inside costs them nothing.

When a cell's bounds look like they take in its walls, Seasons says so as you set them, and `/hof hall check` lists it:

```text
Cell season-2:c1's bounds look like they include its walls (floor, roof, north, south, west, east). A cell's bounds are the space inside: run /hof cell shrink season-2:c1.
```

To fix it, shrink the cell by its walls. The new bounds are shown as outlines first:

```text
/hof cell shrink season-2:c1
/hof cell shrink confirm
```

`/hof gallery shrink season-2` does every such cell of a gallery at once, then `/hof gallery shrink confirm`. Either takes a number of blocks (1 to 4, 1 by default) and `--all` to move every face in, walls or not. A shrink that would leave an anchor outside the cell is refused, `cancel` drops the preview, and `/hof undo` puts the old bounds back.

Until you fix them, `cells.protectShell` (on by default) keeps owners off any wall, floor or roof inside their bounds: they can't break it, but they can still break whatever they placed themselves.

## Many cells at once

- **Tag existing builds:** `/hof cell new <gallery>`, two corners, then anchors, for each cell. Tag them from the hub outwards: that order is used by `ordered` assignment.
- **A row:** select one cell, then `/hof cell row <gallery> <count>` repeats it along the hallway, sharing one-block walls. `--across <direction> <width>` mirrors it on the other side, and `--like <cell>` copies that cell's anchors into each. The row is shown as outlines first; `/hof cell row confirm` writes it.
- **Templates:** build one cell, mark its anchors with structure blocks, save it into `templates/`, and `/hof stamp <template> <gallery> <count>` pastes copies. `alcove` and `plinth` ship with Seasons. See [Anchors](/docs/owners/anchors/#templates).
- **By hand:** edit `galleries/<id>.yml`, then `/season reload`.

Mistakes are fixed in place: `/hof cell swap`, `move`, `rename`, `redefine` and `remove`, and `/hof undo` puts back your last Hall edit.

## Who gets which cell

`cells.assignment` decides, when the decorating window opens:

| Mode | How |
| --- | --- |
| `random` (default) | Everyone who played gets a random free cell, and is told which. Anyone who first plays later gets one at their next login. |
| `claim` | Players pick a free cell with `/hof claim` during the window. |
| `ordered` | The free cells in the order they were tagged, by name or first join. |
| `manual` | Admins assign every cell with `/hof cell assign <cell> <player>`. |

- Any play time earns a cell (`cells.minPlaytime`, default 0).
- `/hof cell assign` before the window opens keeps those cells out of the draw, such as cells near the hub for your leaders.
- Two players swap cells by agreement: both run `/hof swap <player>` within 60 seconds.
- With no free cell left, a player is archived without displays and admins are alerted. `/season end --dry-run` warns about a shortfall in advance.

## Getting into cells

`cells.access`:

| Mode | Behaviour |
| --- | --- |
| `open` (default) | Anyone walks in, in adventure mode, and can look at everything and open view-only containers, changing nothing. |
| `bounds` | Non-owners can't enter a cell; owners only while decorating. Entry is refused at the edge like a wall. |
| `gate` | A gate plane in the doorway is filled with `cells.gateBlock` when shut and cleared when open. Gates are open unless shut on purpose: an admin shuts one (`/hof cell gate`) or a gallery's (`/hof gallery gate`), and an owner their own (`/hof gate shut`). With `cells.gates: auto`, gates shut whenever a cell is locked. |

Nobody is ever teleported out of a cell: a gate waits until its cell and doorway are empty before it shuts. With `cells.moveOut: true`, people are moved to the gallery's entry instead.

## Decorating

- During the window, owners are in survival inside their own cell and decorate with their own blocks.
- An owner can't break a wall, floor or roof that their cell's bounds take in (`cells.protectShell`); see [Select the inside of a cell](#select-the-inside-of-a-cell-not-its-walls).
- Containers are allowed as decoration: chests, barrels, shulker boxes and decorated pots (`cells.containers`). Only the owner opens them while decorating; anyone else sees a view-only copy. Owners can't place hoppers, droppers, dispensers, crafters or ender chests.
- Cells lock when the season ends, before anyone is archived. Whatever was placed becomes protected Hall, and every container opens as a view-only copy from then on, so items left in one are on display forever. A stash gains nothing.
- After archiving, owners get `decorating.afterArchive` (15 minutes) to arrange their cell around their new figure.

## Protection

Inside the Hall: adventure mode, no item dropping or pickup, no ender pearls or chorus fruit, no riding entities in. Displays can't be hit, moved or taken. Players in creative mode and holders of `seasons.hof.bypass` are **builders**: they can change the hub, hallways and archways and place anything, crafting tables and ender chests included. Seasons never removes or changes decoration; it only touches its own figures, displays, gate planes and stamped markers.

Seasons keeps the displays right by itself: every display is checked against the archives and redrawn if anything breaks or goes missing. When you move, add or remove a cell's anchors or change its bounds, that cell is drawn again at once. `/hof reconcile <gallery>` runs that check now, and `/hof restore <player> <season>` redraws one player's displays.

## Keeping a hand-built Hall

If your server already has a hand-built Hall of Fame, you can keep it exactly as it is as a **legacy** gallery, or convert it into Seasons' archives. See [Importing a hand-built Hall](/docs/guides/import-a-hall/).

## Finding your way and fixing mistakes

| Command | Does |
| --- | --- |
| `/hof list` | The hub, the Hall spawn and every gallery with its cells |
| `/hof locate [what]` | Where a gallery, cell, link or player's cell is; with nothing, where you are |
| `/hof hall info` | The hub, the Hall spawn, and how many exits and links |
| `/hof hall exit list` | The Hall's exits; `/hof hall exit none` removes them |
| `/hof gallery exit <gallery>` | Add an exit to a gallery |
| `/hof gallery list` | Every gallery with its cells |
| `/hof gallery remove <gallery>` | Remove a gallery none of whose cells is owned |
| `/hof cell info <cell>` | A cell's owner and anchors |
| `/hof cell list <gallery> [free\|owned]` | A gallery's cells |
| `/hof cell bounds <cell>` | Set a cell's bounds from the selection or two corners |
| `/hof cell redefine <cell>` | Set them again with the wand's next selection |
| `/hof cell rename <cell> <id>` | Give a cell another id |
| `/hof cell move <cell> <dx dy dz>` | Move a cell and its anchors |
| `/hof cell swap <cell> <other>` | Swap two cells' places, keeping ids and owners |
| `/hof cell unassign <cell> [season]` | Free a cell |
| `/hof cell remove <cell>` | Remove a cell with no owner |
| `/hof cell row cancel` | Drop a row you previewed |
| `/hof cell shrink <cell> [n] [--all]` | Move a cell's walls out of its bounds; `confirm` writes it, `cancel` drops it |
| `/hof gallery shrink <gallery> [n] [--all]` | The same for every cell of a gallery; `confirm` or `cancel` |

To get around: `/hof tp hub`, `/hof tp gallery <gallery>`, `/hof tp cell <cell>`, `/hof tp player <player>` and `/hof tp link <link>` go there with no entry checks.
