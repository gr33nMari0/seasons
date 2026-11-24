---
title: Picking a spawn
description: "Choosing the next season's spawn far out in unexplored land: candidates, previews, a vote, and setting the winner."
art: tree
audience: owners
sidebar:
  order: 3
---

Each season starts at a fresh spawn in land nobody has explored, with the newest terrain. Seasons helps you shortlist candidates, show them to the group, and set the winner.

## 1. Find candidates

- Go **far out**: well past your old world, in directions nobody has explored. Your web map shows which land has been generated. Leave at least 2,000 blocks between the edge of your old-world zone and the spawn; Seasons refuses a spawn inside a zone.
- Check a **stronghold** is within reach of each, so the season can be finished: `/locate structure stronghold`.
- `/season status` shows the season's furthest block placed from 0, 0, a good guide to how far out "unexplored" starts.

## 2. Mark them

```text
/season spawn candidate add north
/season spawn candidate add east 18400 72 -2100
```

`/season spawn candidate list` shows them with their distance from 0, 0; `move` and `remove` fix mistakes; `/season tp candidate <name>` goes there. Candidates appear on BlueMap until launch.

## 3. Let the group look

Pre-generate a small area (say 500 blocks) around each candidate with a pre-generator such as Chunky, and add one bounded BlueMap map per candidate, so people can vote on the terrain without the whole new area being revealed. Post the previews and a poll.

## 4. Set the winner

```text
/season spawn set north
```

or stand there and run `/season spawn set`, or type a position with `/season spawn set at <x y z>`. `/season spawn show` confirms it. Pre-generate around it (a few thousand blocks) before launch, and keep it unrendered on your map until then.

Launch moves the world spawn to the new spawn (`launch.setWorldSpawn: true`). Set it to `false` to keep the world spawn in the Hall, so first-time joiners and bedless respawns arrive in the hub.

## Past seasons' spawns

After launch, record the old spawn so links can lead back there: `/season spawn record <season>` where you stand. See [Links](/docs/owners/links/).

## Managing spawns

| Command | Does |
| --- | --- |
| `/season spawn candidate move <name>` | Move a candidate where you stand, or to a typed position |
| `/season spawn candidate remove <name>` | Remove a candidate |
| `/season spawn clear [season]` | Clear the next season's spawn, or a past season's recorded one |
| `/season tp spawn [season]` | Go to a season's spawn, this season's by default |
| `/season list` | Every season Seasons knows of, with a file or a gallery |
