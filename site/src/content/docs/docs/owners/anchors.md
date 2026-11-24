---
title: Anchors
description: The marked spots in a cell where Seasons puts the figure, the archive chest, the plaque, trophies, the entry and the gate, and how to place, see and move them.
art: figure-diamond
audience: admins
sidebar:
  order: 6
---

Anchors are the marked spots inside a cell's bounds where Seasons puts things. Every anchor sits inside its cell's bounds, except a gate's two corners, which may sit in the layer just outside them so the gate fills the doorway.

## The six anchors

| Anchor | Needed | How many | What Seasons does there |
| --- | --- | --- | --- |
| **figure** | Yes, in a season gallery | 1 | Spawns a mannequin wearing the player's archived armour and held items, in their skin from that season, named "Alex · Season 3" |
| **archive** | No | Any | The block there (a real chest, a barrel, anything) opens the read-only archive view of everything else they carried, with full tooltips |
| **plaque** | No | 0 or 1 | The season card: a book on a lectern or a text display, with stats, firsts, awards and the advancement count |
| **trophy** | No | Any | Item displays for awards. In a museum, a plinth's item or a season's dragon egg |
| **entry** | No | 0 or 1 | Where `/hof <player>` lands visitors: a block, or a box up to 32 blocks a side they're spread over |
| **gate** | No | 0 or 1 | A plane of blocks Seasons fills and clears when access mode is `gate`. Only air is ever filled, so decoration in the plane stays |

Clicking the figure opens the same archive view as the archive anchor, because figures can't show tooltips in the world.

## Placing anchors with the wand

1. Run `/hof wand` and hold it.
2. Select the cell: `/hof cell new <gallery>` and two corners, or stand in an existing cell.
3. **Right-click a block** with the wand and pick an anchor type from the menu.

Or by command: `/hof cell anchor <cell> <type>` at the block you look at, or at a typed position.

`/hof wand mode place` makes the wand pick the block a placed block would fill instead of the block clicked; swap-hands (F) toggles it.

## Seeing them

While you hold the wand (or after `/hof show on`), the hub, galleries, cells, gates, entries, exits and links nearby are drawn as glowing outlines with their names, seen only by you. Every anchor gets a marker in its type's colour and a label within 12 blocks (`wand.anchors.labelRange`). The action bar names the cell your next selection sets, and says what that cell has and still lacks.

## Moving and removing them

- **Pick it up:** right-click the anchor with the wand and choose **Move**. Sneak and scroll to turn it, then right-click a block inside the cell to put it down.
- **By command:** `/hof cell anchors <cell>` lists a cell's anchors, numbered. `/hof cell anchor move <cell> <type> [#n] <position>` moves one; `/hof cell unanchor <cell> <type> [#n]` removes one, or all of a type.
- **Undo:** `/hof undo` puts back your last Hall edit.

## Plaque size

A plaque's text is 1.0 by default, 0.25 to 4. Sneak and scroll while looking at a plaque with the wand to resize it, or `/hof cell plaque <cell> scale <n>`, or a whole gallery's with `/hof gallery plaque <gallery> scale <n>`.

## Editing boxes in place

Left-click any box with the wand (a cell, a gallery, the hub, a link's area, a point's box) to take it for editing, with the face you clicked picked. Sneak and scroll moves that face by a step; drop (Q) cycles the steps (1 and 10 by default); swap-hands (F) or `/hof sel apply` writes it. `/hof sel expand`, `contract`, `shift` and `face` do the same by command, and `/hof sel cancel` puts the box back.

## Templates

Build one cell in creative, then mark its anchors with **structure blocks in DATA mode**, with metadata:

| Metadata | Anchor |
| --- | --- |
| `seasons:figure` | figure |
| `seasons:archive` | archive |
| `seasons:plaque` | plaque |
| `seasons:trophy` | trophy |
| `seasons:entry` | entry |
| `seasons:gate` | a gate corner (use two) |

Save the cell with a structure block into the `templates/` folder. `/hof stamp <template> <gallery> <count>` pastes copies where you stand (`--step x y z` between them, `--rotate 0|90|180|270`), registers each marker as an anchor and replaces it with air. Two templates ship with Seasons: `alcove` and `plinth`.

## Copying a cell's anchors along a row

Select a finished cell and run `/hof cell row <gallery> <count> --like <cell>`: each new cell gets the same anchors and gate, mirrored on the far side with `--across`.
