---
title: Old-world zones
description: "Lock old land until Homecoming with zones you draw: block, no-build or announce modes, shapes, unlock conditions and the Hall exemption."
art: house
audience: owners
sidebar:
  order: 4
---

Zones are how Seasons locks the old world. Seasons doesn't know where your old land is, so you draw it. **Until a zone covers a piece of land, nothing locks it:** links, `/tp`, walking and portals all get through.

## What a zone is

A zone is a shape in one dimension, with:

- a **mode** while locked: `block`, `no-build`, `announce` or `off`;
- an **unlock condition**: manual by default, which means Homecoming;
- a mode **after it unlocks**: `off` by default, or `no-build`.

The Hall is always exempt, even inside a zone. That's why `/hof` keeps working when the Hall sits in the middle of the old world (see [The Hall exemption](#the-hall-exemption)).

## The modes

| Mode | What it does |
| --- | --- |
| `block` | Nobody gets in. Walking in, teleports (Hall links included), ender pearls, chorus fruit and portals that would land inside are refused or sent back. Nothing inside can be broken, placed, opened or taken, even from outside the edge: pistons, hoppers, liquids, fire and explosions can't cross it. A player who logs in inside one goes to the season spawn. |
| `no-build` | Anyone can walk in, use doors, farms and containers, and trade, but nobody can place or break anything. Explosions and fire break and burn nothing. |
| `announce` | Nothing is blocked. Entering is broadcast once per player per zone per day, such as "Alex entered the market (opens at Homecoming)", and optionally posted to Discord (`zones.announceToDiscord`). |
| `off` | Does nothing. Every zone ends up here after Homecoming, by default. |

Only holders of `seasons.zone.bypass` ignore zones. **Nobody has it by default, not even ops**, but a LuckPerms `*` grants it, so test with a normal player.

## Locking the old world

1. Stand at 0, 0 in the overworld (or the middle of your old builds) and run:

   ```text
   /season zone add old-world cylinder <radius>
   ```

   Make the radius reach just past the furthest old build, **old season spawns included**. Your web map shows how far that is.
2. Give any build outside the circle its own box: select it with the wand (`/hof wand`, two corners), then `/season zone add <id> box`.
3. In the Nether, do the same at an eighth of the radius, up to y 127 so the roof stays open: `/season zone add old-nether cylinder <radius/8> --max-y 127`.
4. Keep the next season's spawn outside every zone. Seasons refuses a spawn set inside one; leave a margin of 2,000 blocks or more.

New zones are `block` until Homecoming and `off` after it, so `add` is usually all you need. The End has no zones: its main island is shared every season. To freshen the outer End, see [Regenerating the End](/docs/guides/end-regeneration/).

## Shapes

| Shape | Example |
| --- | --- |
| Cylinder around where you stand | `/season zone add old-world cylinder 6000` |
| Box from the wand selection | `/season zone add iron-farm box` |
| Box from two corners | `/season zone add iron-farm box 100 0 100 180 320 160` |
| Outside a circle (a frontier you can't leave) | `/season zone add frontier outside circle 5000` |
| Outside a box | `/season zone add frontier outside box -5000 -5000 5000 5000` |
| Above or below a height | `/season zone add sky y-above 256`, `/season zone add deep y-below -32` |

`zones.frontierBorder: true` draws a locked `outside` circle as a world border each player inside it sees. It is visual only; the zone does the locking.

## Managing zones

| Command | Does |
| --- | --- |
| `/season zone list` | Every zone, its mode now and its mode after Homecoming |
| `/season zone info <id>` | One zone in detail |
| `/season zone mode <id> <mode>` | What it stops while locked |
| `/season zone after <id> off\|no-build` | What it becomes after Homecoming |
| `/season zone remove <id>` | Remove it |
| `/season unlock <id>` / `/season lock <id>` | Unlock or relock one zone by hand |
| `/season unlock homecoming` | Unlock every zone at once (see [Homecoming](/docs/owners/homecoming/)) |

With BlueMap installed, locked zones show as areas on your map, and as no-build areas after Homecoming where that applies.

## The Hall exemption

The Hall gets no zone enforcement at all, even inside the `old-world` circle. Exempt means exactly **the hub's box plus every gallery's box**:

- Players can arrive by `/hof` or a link, walk the galleries and take links. Only the Hall's own rules apply there.
- Anything outside those boxes isn't exempt: an entrance path or roof that sticks out past the hub, or a hub with no bounds set. Run `/hof hall check` to see what's set.
- Leaving the Hall into the zone is still refused, and so are links from the Hall into locked land. The Hall is an island in the locked circle, not a way into it.
- `/hof leave` is unaffected: it takes players back where they came from, outside the zones.

## Locked links and spawns

A link into land that's locked for that player is refused with the reason, such as "That leads into land locked until Homecoming", and works once Homecoming opens it. If a link to an old season's spawn lets people through before Homecoming, the spawn isn't inside a `block` zone, the tester holds `seasons.zone.bypass`, or Homecoming has already happened. See [Troubleshooting](/docs/guides/troubleshooting/#links-let-players-into-the-old-world).
