---
title: Item safety
description: "How Seasons makes sure every item exists exactly once: the journal, crash recovery, display copies, the tripwire, reconciliation and the audit log."
art: shield
audience: owners
sidebar:
  order: 16
---

Seasons moves a lot of items: everyone's gear at the end of a season, ender chests into vaults, withdrawals, museum items, curation. It is built so that **a crash at any moment leaves every item exactly once**: archived or still carried, never both and never lost.

## The journal

Before any item moves, Seasons writes what it is about to do to an append-only journal, with a hash of the items involved. Then it does it, then it writes that it's done.

On startup, Seasons replays the journal. Anything started but not finished is checked against the player's actual inventory: if the items are still there, the step resumes; if they're already gone, it's marked done. Every step is keyed by player and season, so a rerun changes nothing. `/season status` shows the journal's health; `/season reprocess <player>` reruns a player's pending steps.

## No real items in the Hall

The figures, trophies and archive chests show **copies**, tagged `seasons:archived`. The items themselves live in Seasons' own files, never in the world:

- The archive view is built from a throwaway copy, and every click in it is refused.
- Archive anchor blocks have no real container behind them, so nothing can be hoppered out, broken open or exploded.
- Containers players decorated their cells with become view-only copies when the cell locks.

## The tripwire

A tagged copy that somehow escapes into the world (an inventory, a bundle, a shulker box, a dropped item) is caught on sight, removed and reported to admins and the admin webhook.

## Self-healing displays

Displays are rebuilt from the archives, never the other way round. Seasons checks each gallery's figures, plaques and displays against the records and redraws anything broken or missing. `/hof reconcile <gallery>` runs that check now; `/hof restore <player> <season>` redraws one player's.

## The audit log

An append-only log records archiving and undoing it, vault withdrawals, curation, museum changes, config changes (path, old value, new value, who), zone mode changes, Homecoming, tripwire hits, repairs and recovery runs. `/season audit [player]` shows recent entries.

## Locked down by default

- Player commands need nothing; admin and curation commands need op or their permission node.
- `seasons.zone.bypass` and `seasons.hof.bypass` are granted to nobody by default, not even ops.
- Reloads are all-or-nothing: a broken file is reported with its line, and the old settings stay in force.
- `/season reset` wipes Seasons from a test world, and only server operators can run it, never through a permission, after two confirmations and typing DELETE. (`/season reset confirm`, then DELETE in chat; `--keep-hall` keeps the Hall's layout).

## Your part

- **Back up** the world folder and `plugins/` before every rollover and every update.
- **Rehearse** the rollover on a copy of the world first.
- **Report dupes privately.** Never post an exploit in public: see the [security policy](https://github.com/gr33nMari0/seasons/blob/main/SECURITY.md).
