---
title: Curation
description: "Moving an item out of an archive the only way it can be: saved, logged and announced. Museum, return, take and destroy."
art: shield
audience: admins
sidebar:
  order: 12
---

Archives are view-only forever, and the archive view never becomes an editable chest. **Curators** can still move a single item out, through a curation action that is saved, audited and announced. It's for the rare case (a mob bucket the owner wants back, a heavy core for the museum), not a routine.

## Opening the curator view

`/hof curate <player> <season>`, or sneak-click a cell's archive anchor or figure, with `seasons.curate.view`. It shows the figure's equipment and the archive contents, with full tooltips.

## Actions

Click an item, pick an action, and type a reason in chat (`curate.requireReason`; nobody else sees it).

| Action | Where the item goes | Permission |
| --- | --- | --- |
| **Museum** | Straight onto a museum plinth with a plaque, never through anyone's inventory | `seasons.curate.museum` |
| **Return to owner** | The Returned page of the owner's ender vault, which they can take from at once, even offline and before Homecoming | `seasons.curate.return` |
| **Take** | Your inventory, as a normal item | `seasons.curate.take` |
| **Destroy** | Removed and kept 30 days in a recycle bin; `/hof curate undo <id>` restores it | `seasons.curate.destroy` |

Each action removes the item from the record, saves it, then delivers the item. If delivery fails (a full inventory, a missing plinth), the removal is rolled back. Removing equipped gear redraws the figure.

## Who can curate

The nodes default to ops, who can already spawn any item, so curation adds no new power. Give a trusted group the lot with `seasons.curate.*`:

```text
/lp group curators permission set seasons.curate.* true
```

## Transparency

Every action writes an audit entry (the item, owner, season, slot, destination, curator and reason), posts to the admin webhook, and tells the owner in game (at their next login if offline) and on Discord.
