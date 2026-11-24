---
title: Upgrading
description: How to update Seasons safely, and what to do after each release that needs it.
art: chest
audience: owners
sidebar:
  order: 4
---

## Every update

1. Read the [changelog](/changelog/) for every version between yours and the new one.
2. Back up the world folder and `plugins/`.
3. Stop the server, replace `Seasons-<version>.jar` (and `SeasonsWeb-<version>.jar`, which is always released with it), and start again.
4. Read the startup lines. If something needs your attention, the last line says which command lists it.

Every Seasons file records its format version, so a newer Seasons reads older files. Archives store items as vanilla item data, which the game upgrades when it loads them, so they survive game updates too. Updating Seasons and the game separately is safest: one change at a time.

## From 1.3 to 1.4

1.4 is the design overhaul of the dashboard and these docs. Nothing to do in game. If you run Seasons Web, add your game address to `web.yml` so players can find your dashboard from the address they play on (see [Let players find your dashboard](/docs/guides/publish-dashboard/)), then run `/web discovery` for the record to publish.

## From 1.2 to 1.3

1.3 reworked links. Links moved from `hall.yml` to `<world>/seasons/links.yml`, copied once on the first start; `hall.yml` is left as it was, so going back to 1.2 is swapping the jar.

1. After the first start, read `/hof link upgrade report`. It lists every link copied, each trigger before and after.
2. Buttons, signs, doors and levers became **click-only**: standing on one no longer takes it. Click each link once and stand on each pressure plate to check.
3. On any link meant to be walked onto, put `both` back: `/hof link trigger kind <id> 1 both`.
4. Run `/hof link upgrade done` to stop the reminder.

To go back, stop the server, put the 1.2 jar back and start: it reads `hall.yml`'s links as they were at the upgrade. `/hof link upgrade import --replace` copies them again later, and `/hof link upgrade forget-old` drops the old copy once you're sure.
