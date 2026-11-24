---
title: Troubleshooting
description: What you see, why it happens, and what to do, for the problems server owners meet most.
art: hourglass
audience: owners
sidebar:
  order: 6
---

Start with the console: at startup Seasons writes a short summary, and its last line says which command lists anything that needs attention. `/season status` and `/hof hall check` cover most setup problems.

## Links let players into the old world

A link to an old season's spawn should say "That leads into land locked until Homecoming". If it lets players through, one of these is true:

1. **No `block` zone covers that spawn.** Run `/season zone list`. With no zones, nothing is locked. Draw `old-world` so it reaches past every old spawn: see [Old-world zones](/docs/owners/zones/).
2. **The tester skips zones.** Only `seasons.zone.bypass` does that; nobody has it by default, but a LuckPerms `*` grants it. Ops also have `seasons.hof.anywhere`, so `/hof` works for them with no bed near. Test with a normal player.
3. **Homecoming has happened.** `/season status`: after Homecoming, the old world is open by design.

To make a link wait for Homecoming whatever covers its destination: `/hof link when <link> after-homecoming`.

## "/hof" is refused

The message says which check failed. Defaults: within 3 blocks of any bed, no monsters near, no damage in the last 10 seconds, on the ground, not in lava, fire or powder snow, at most one try every 5 seconds. Each is a setting under `hof.entry`. A link to the Hall skips the bed check.

## Standing on a button or sign doesn't take the link

Since 1.3, block links fire on a click, except pressure plates and tripwire. To fire underfoot too: `/hof link trigger kind <link> <n> both`.

## A player has no cell

`/season end --dry-run` reports a shortfall. Add cells (`/hof cell row`, `/hof stamp`), or set `cells.minPlaytime` so people who barely played don't take one. A player archived without a free cell keeps their archive; give them a cell and run `/hof restore <player> <season>` to draw it.

## A figure or plaque is missing or wrong

`/hof reconcile <gallery>` checks and redraws the gallery's displays; `/hof restore <player> <season>` redraws one player's. Displays are always rebuilt from the archives, so nothing is lost.

## "That's in the <zone>, which is locked"

A `block` zone refuses changes across its edge too: pistons, hoppers, liquids and explosions can't reach in from outside. Build further from the edge, or `/season unlock <zone>` that zone early.

## A reload did nothing

`/season reload` is all or nothing. If any file has an error, the old settings stay and the error (file, line, what's wrong) goes to the console and to online admins. `/season validate` checks without reloading.

## Players can't find the dashboard

Check `enabled: true`, an https `public-url`, and a tunnel or proxy in front of the `bind` address. Then publish and check the discovery record: [Let players find your dashboard](/docs/guides/publish-dashboard/).

## Chasing anything else

List areas under `logging.debug` in `config.yml` (`hall`, `archive`, `zones`, `discord`, `maps`, `commands`), run `/season reload`, reproduce the problem, read the log, and turn them off again. Then [open an issue](https://github.com/gr33nMari0/seasons/issues) with what you found. Never post a dupe or exploit in public: see the [security policy](https://github.com/gr33nMari0/seasons/blob/main/SECURITY.md).
