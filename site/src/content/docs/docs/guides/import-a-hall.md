---
title: Importing a hand-built Hall
description: Keep a hand-built Hall of Fame exactly as it is as a legacy gallery, or convert its cells into Seasons archives with /hof import.
art: chest
audience: owners
sidebar:
  order: 2
---

If your server already has a hand-built Hall of Fame, such as cells with an armour stand wearing each player's gear and chests of the rest, you have two choices.

## Keep it as it is

Make it a **legacy** gallery:

```text
/hof gallery create season-1 1 --legacy
```

Barriers, chests and armour stands stay, protected as Hall: chests open as view-only copies for everyone but builders, and nobody but builders breaks a container with something in it or touches a stand's gear. Seasons never draws displays there, never assigns or gates its cells, and doesn't ask for figures. Visitors still visit: `/hof 1` lands at its entry when Season 1 has no other gallery with one. You can still convert it later.

## Convert it

`/hof import` turns each cell into the same archive a rollover makes, without losing or reordering an item, and the hallway looks the same afterwards. Do it on a **copy** of the world first.

1. **Tag the gallery and cells.** `/hof gallery create season-1 1`, then `/hof cell new season-1 <id>` and two wand corners for each cell. No anchors needed: the import finds them.
2. **Dry run.** `/hof import season-1 --dry-run` scans every cell and writes `import-season-1.txt`: for each cell, the owner found, the stand's gear, each container and its item count, item frames, and anything it can't place. Nothing changes.
3. **Owners.** The owner comes from the player head on the stand. A cell with no head, two heads or someone else's is flagged; set it with `/hof cell assign <cell> <player>` and dry-run again.
4. **Import.** `/hof import season-1`. It's journalled like an archive, so a crash resumes where it stopped.
5. **Check.** Each cell's item counts and a hash of every item are compared before and after, written to the audit log, and the import stops at the first mismatch. Then `/hof reconcile season-1` and walk the hallway.
6. **Undo.** `/hof import undo season-1 [cell]` puts every item back exactly where it came from.

| In the hand-built cell | After the import |
| --- | --- |
| Armour stand with armour, held items and the player's head | A mannequin wearing the same gear, in the skin from the head. The stand is removed |
| Each chest, barrel or shulker box (a double chest counts as one) | One page of the archive view, in the same slot order. The container stays as an empty, protected archive anchor that opens the view at its page |
| Shulker boxes inside chests | Kept whole, and shown in the tooltip |
| Items in item frames | Trophy anchors showing the item |
| Signs, books and other decoration | Left in place; sign text is copied onto the season card |
| Statistics and advancements | Unknown, so the card says "Season 1 · imported" |

Remove the old barrier blocks afterwards: the Hall's own protection takes over.
