---
title: Homecoming
description: Opening the old world for everyone at once, what changes, and unlocking zones one at a time.
art: lock-open
audience: owners
sidebar:
  order: 3
---

Until Homecoming the old world is locked: nobody can enter old seasons' land, change it or take anything from it, and ender chests stay sealed in each player's vault. **Homecoming unlocks the whole world at once**, so old bases, chests and items are playable again and megaprojects carry on. Nothing is ever wiped.

## Running it

When the group votes for it:

```text
/season unlock homecoming
```

For everyone at once, in the same tick:

- a title and sound for everyone online, a chat broadcast, a Discord post and fireworks at the spawn;
- every old-world zone switches to its after-Homecoming mode (`off` by default: fully open; or `no-build`);
- every ender vault becomes withdraw-only for everything (see [The ender vault](/docs/owners/vault/));
- BlueMap's locked areas are replaced by no-build areas where that applies.

The season moves from its **Frontier** stage to **Open**. Homecoming happens once per season. The Hall of Fame stays view-only: Homecoming unlocks the world, never the archives.

## Unlocking on a schedule

Each zone has an unlock condition. The default, **manual**, waits for the command. A zone can instead unlock on its own:

| Kind | Example |
| --- | --- |
| Manual | `/season unlock homecoming` |
| Fixed date | `date: 2027-01-10T19:00+11:00` |
| Time after launch | `after: 21d` |
| A milestone | `dragonKilled`, `playersReachedEnd: 3` |
| A combination | `any: [...]`, `all: [...]` |

Set them in the season's zone file and `/season reload`. The manual command always works as an override, whatever the condition.

## One zone at a time

- `/season unlock <zone>` opens one zone early, such as a shared farm.
- `/season lock <zone>` locks it again.
- `/season zone after <zone> no-build` makes a zone no-build after Homecoming instead of open, for a build everyone should be able to visit but not change.

## Links and Homecoming

Links to old seasons' spawns are refused while those spawns are in locked land ("That leads into land locked until Homecoming"), and work once Homecoming opens it. A link can also wait for Homecoming whether or not a zone covers where it leads: `/hof link when <link> after-homecoming`.
