---
title: Regenerating the End
description: Prune the End's untouched outer islands with MCA Selector so cities and elytras come back, while keeping the main island, farms and gateways.
art: egg
audience: owners
sidebar:
  order: 1
---

Seasons never trims or regenerates chunks, and the End has no zones: its main island is shared every season. To bring back end cities and elytras for a new season, delete the outer-island chunks nobody really used, with [MCA Selector](https://github.com/Querz/mcaselector), while the server is stopped. The game regenerates them as players explore.

## Before you start

- Stop the server and take a **full backup**.
- Work on a **copy** of the world first.
- On Paper 26.x the End's region files are in `world/dimensions/minecraft/the_end/region`. Point MCA Selector there.

## Choose what to delete

1. **Barely-visited chunks.** Use MCA Selector's filter to select chunks by low `InhabitedTime`, such as under a few minutes. The exact syntax is in its query dialog, and it shows the count before you confirm. A chunk someone only flew over has almost no time; a base or farm has hours.
2. **Leave out the main island and its surroundings.** Deselect about ±1,000 blocks around 0, 0 (regions −2 to 1 on each axis). That keeps the dragon's island, its 20 gateways, and anything built in the void beside it, such as an enderman farm.
3. **Leave out your builds and farms by hand,** plus a chunk or two around each, to be safe.
4. **Keep the chunks around every outer gateway players have used.** The inner gateway remembers where its exit is; if the outer gateway's chunk is deleted, the regenerated land won't have the return gateway, and players could be stranded. If MCA Selector's query can match `end_gateway` in a chunk's palette, deselect those chunks; otherwise deselect the gateways you know.
5. **Delete in large connected blocks,** not scattered chunks. End cities span several chunks, so a city next to a kept chunk can come back cut in half.

## After deleting

1. Start the copy and fly out past your selection's edge. New islands, cities and ships should generate.
2. Repeat on the real world, after a fresh backup.
3. Re-render the End on your web map.

A city that was looted but only briefly visited may be selected and regenerate, which is usually what you want. One someone lingered in won't be. Raise or lower the threshold to taste.
