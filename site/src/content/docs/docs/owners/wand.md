---
title: The wand
description: Selecting boxes, placing anchors, making links anywhere, nudging faces and seeing the Hall's outlines.
art: shield
audience: admins
sidebar:
  order: 9
---

`/hof wand` gives Hall admins the wand: a blaze rod marked as Seasons' own (`wand.item`), so a plain one does nothing. It does four jobs.

## 1. Select boxes

- **Sneak-click** two blocks to set the corners of a selection.
- `/hof wand mode block|place`: take the block clicked, or the block a placed block would fill. Swap-hands (F) toggles it.
- By command: `/hof sel pos1`, `/hof sel pos2`, `/hof sel info`, `/hof sel clear`.

Commands that take a box use the selection when you give none: `/hof hall hub`, `/hof gallery bounds <id>`, `/hof cell new <gallery>`, `/season zone add <id> box`, and any command that accepts `sel`.

## 2. Place anchors and make links

**Right-click** a block for its menu. In a cell: an anchor type (figure, archive, plaque, trophy, entry, gate; in a museum, an egg plinth). Anywhere, in any world: **Make a link**, or set the block aside for the next `/hof link add`. In the Hall it also sets the Hall spawn, exits and a gallery's entry from the block or the selection. A plaque faces the side of the block you clicked. Setting a cell's bounds with the wand warns you when they look like they take in the cell's walls (see [Select the inside of a cell](/docs/owners/the-hall/#select-the-inside-of-a-cell-not-its-walls)). See [Anchors](/docs/owners/anchors/) and [Links](/docs/owners/links/).

## 3. See everything

While you hold the wand, or after `/hof show on`, the hub, galleries, cells, gates, entries, exits, links and points nearby are drawn as glowing outlines with their names, for you alone. Anchors get a marker in their type's colour. Up to 64 boxes within 48 blocks are drawn (`wand.outlines`). `/hof show off` hides them.

## 4. Edit boxes in place

**Left-click** a box (a cell, a gallery, the hub, a link's area, a point's box) to take it for editing, with the face you clicked picked.

- **Sneak and scroll** moves that face by a step.
- **Drop (Q)** cycles the steps (`wand.nudge.steps`, 1 and 10 blocks).
- **Swap-hands (F)** writes the box through its own command, as `/hof sel apply` does.
- `/hof sel expand [n] [face]`, `/hof sel contract [n] [face]`, `/hof sel shift [n] [direction]`, `/hof sel face <face>`, `/hof sel step [step]`, `/hof sel edit <what>` and `/hof sel cancel` do the same by command.

Anchors move too: right-click one with the wand and choose **Move**, sneak-scroll to turn it, right-click a block in its cell to put it down. `/hof undo` puts back your last Hall edit.
