---
title: The museum
description: "A gallery with no owners for one-off trophies and each season's dragon egg: making one, plinths, the egg wall and putting items on display."
art: egg
audience: admins
sidebar:
  order: 7
---

The museum is a gallery of kind `museum`. It has no owners, and each of its cells is a **plinth** that shows one item, display-only, forever. It's the home of one-off trophies, and of each season's dragon egg.

## Making a museum

1. **Build it**, anywhere. A museum that doesn't touch the hub is allowed; `/hof hall check` warns, because walking across the gap means leaving the Hall. Connect it, or give players a [link](/docs/owners/links/) there.
2. **Create the gallery and set its bounds:**

   ```text
   /hof gallery create museum 1
   /hof gallery bounds museum
   /hof gallery entry museum
   ```

3. **Make it a museum.** There's no command for the kind: open `<world>/seasons/galleries/museum.yml`, change `kind: season` to `kind: museum`, then run `/season reload`.
4. **Add plinths.** For each one, `/hof cell new museum <id>` and two wand corners, then right-click a block with the wand and pick **trophy**: that's where the item floats. Add a **plaque** anchor for its label if you like.

## The egg wall

In a museum, the wand's anchor menu also offers **Egg plinth for Season N**: a trophy anchor tagged with that season. When a season ends, its plinth shows a dragon egg labelled "Season 3 · slain by Alex", with the season's awards listed below. A season with no dragon slayer shows the awards with no egg.

`/season end --dry-run` warns when no museum has an egg plinth for the season ending.

The real egg goes to the player who slew the dragon: each season's first dragon kill drops a new egg (`dragon.newEggEachSeason`), and Seasons hands it to the slayer with a fanfare, or to the Returned page of their vault if they can't take it.

## Putting items on display

Hold the item and run:

```text
/museum add museum:<plinth> [label]
```

The item goes into Seasons' store, journalled, and a display copy appears on the plinth with a plaque line: the label, the season and who put it there. `/museum remove museum:<plinth>` gives it back, whole or not at all.

- A hand-placed item on an egg plinth is shown instead of the season's worked-out egg, such as the server's original dragon egg.
- Archived items can't go in with `/museum add`. A curator moves one from an archive with [curation](/docs/owners/curation/) instead, straight onto a plinth.
- Each add and removal is in the audit log.
