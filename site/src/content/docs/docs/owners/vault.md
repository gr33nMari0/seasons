---
title: The ender vault
description: What happens to ender chests at the end of a season, the vault before and after Homecoming, and the Returned page.
art: chest
audience: owners
sidebar:
  order: 10
---

Ender chests count as old-world storage. At the end of a season each player's ender chest is sealed into their **ender vault**, and it opens at Homecoming.

| When | `/vault` |
| --- | --- |
| Before Homecoming | Shows the contents, read-only |
| After Homecoming | Withdraw-only: click an item to take it |
| Any time | The **Returned** page (curator returns, a dragon egg that couldn't be handed over) can be taken from at once |

Each withdrawal is a transaction: the item comes off the record, the record is saved, then the item is given. If the give fails (a full inventory), the removal is rolled back. The menu never moves items itself, and nothing can be put in. A player with vaults from several seasons sees one page per season.

## Policies

`resets.enderChest` decides what happens to ender chests at archiving:

| Policy | Ender chest contents |
| --- | --- |
| `vault` (default) | Sealed in the vault, as above |
| `archive` | Archived with the rest of the player's gear, view-only in their cell, never returned |
| `keep` | Left where they are, usable all season |
