---
title: Season cards, awards and firsts
description: Each player's season card, the awards at the end of a season, season firsts, the dragon egg and the deaths display.
art: firework
audience: everyone
sidebar:
  order: 13
---

Seasons takes a snapshot of every player's vanilla statistics when a season starts and compares it when they're archived. The difference, plus advancements and the furthest block placed, becomes a season card, the awards and the season firsts.

## The season card

On each cell's plaque: a book on a lectern, or a text display.

- Name, season, first and last day played.
- Highlights: play time, deaths, mob and player kills, distance travelled (walking, swimming, flying, elytra, boats, minecarts and horses together), diamonds and ancient debris mined, blocks placed and mined, advancements (such as "88 / 122"), the furthest block placed, dragon credit, and where they ended the season.
- Every season first they earned, and their awards.
- Further pages: every General statistic as this season's difference. Seasons Web's profile shows the full per-item, per-block and per-mob tables.

Advancements reset every season (`resets.advancements`), so everyone starts at zero and every advancement is a race again.

## Awards

Worked out when the season ends and announced during the Intermission. Each winner gets a trophy item on a free trophy anchor in their cell, and a line on the museum's season plaque. Ties share an award; an award whose best is zero goes to nobody.

| Award | Measures | Trophy |
| --- | --- | --- |
| Most deaths | Deaths | Skeleton skull |
| Furthest travelled | Distance travelled | Compass |
| Most dedicated | Play time | Clock |
| Deepest pockets | Diamonds mined | Diamond |
| Dragon slayer | The season's dragon slayer | Dragon head |
| Furthest builder | Furthest block placed from the season spawn | Spyglass |
| Most advancements | Advancements this season | Knowledge book |

Change, add or turn off awards under `awards` in `config.yml`: each has a `measure` and an `item`; `measure: off` turns one off.

## Season firsts

Broadcast in chat and on Discord, and recorded on the season card:

- First to the Nether, first diamond, first to the End, first elytra, and the dragon slayer.
- First to each advancement.
- First to hold each item on `firsts.rareItems`, such as a heavy core, a totem of undying, a trident or a nether star.

`/season firsts` lists the season's firsts and awards.

## A dragon egg every season

Each launch clears the End's previously-killed flag (`dragon.newEggEachSeason`), so the season's first dragon kill drops a new egg. The **slayer** is whoever caused the final blow, an arrow or a crystal they set off included; failing that, whoever dealt the most damage. Seasons gives them the egg with a fanfare: a title for everyone, fireworks over the portal, a broadcast and a Discord post. If they can't take it, it goes to the Returned page of their vault. With `dragon.eggTo: portal`, the egg stays on the portal as in the game.

## Deaths

Each season gets its own vanilla scoreboard objective, such as `seasons_deaths_s3`, so history is kept. It's shown in the tab list by default (`deaths.display`: `list`, `sidebar`, `below_name` or `none`).
