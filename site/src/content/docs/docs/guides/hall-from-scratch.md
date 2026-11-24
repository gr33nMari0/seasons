---
title: A Hall from scratch
description: Build a hub and one gallery of cells, mark them with the wand, and check them, in about twenty minutes.
art: trophy
audience: admins
sidebar:
  order: 4
---

This walks through a small Hall: a hub with one wing of ten cells for Season 3. Do it in creative.

## 1. The hub

Build a room for the hub. Then:

```text
/hof wand
```

Sneak-click two opposite corners of the room's inside with the wand, then:

```text
/hof hall hub
```

Stand where visitors should arrive and run `/hof hall spawn`. Hold the wand and you'll see the hub outlined.

## 2. The wing

Build a hallway off the hub, touching it, with room for cells on one or both sides. Select the hallway's corners (cells included) and run:

```text
/hof gallery create season-3 3
/hof gallery bounds season-3
```

Stand at the hallway's entrance and run `/hof gallery entry season-3`.

## 3. The first cell

Build one cell. Then:

```text
/hof cell new season-3
```

Sneak-click two corners of the cell's inside (floor not included). Now right-click blocks with the wand and pick:

- **figure** where the mannequin should stand;
- **archive** on a chest or barrel;
- **plaque** on a lectern or a wall block;
- **trophy** on a shelf or two, for awards.

## 4. The rest of the row

Select that cell again, then repeat it along the hallway:

```text
/hof cell row season-3 9 --like c1
```

Add `--across +z 7` (the direction and width of the hallway) to mirror them on the other side. Check the outlines, then `/hof cell row confirm`.

Or skip building cells and paste templates instead: `/hof stamp alcove season-3 10`.

## 5. Check

```text
/hof hall check
```

Fix what it reports. When it's clear, `/hof gallery info season-3` lists the cells, and you're ready for the decorating window.
