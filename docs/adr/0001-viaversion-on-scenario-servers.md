# 1. ViaVersion and ViaBackwards on scenario servers

Date: 2026-11-17 · Status: accepted

## Context

The acceptance scenarios use Mineflayer bots as players. Mineflayer 4.39.0, the newest release at M0, speaks Minecraft protocols up to 26.1, while Seasons v1 targets 26.3 only. A 26.1 client can't join a 26.3 server.

## Decision

The scenario servers (`testkit`, `scenarioRun_*` tasks) download ViaVersion and ViaBackwards from Modrinth through run-paper's download service, pinned in `gradle/libs.versions.toml`. The bots connect as 26.1 clients and ViaBackwards translates. Nothing ships with them: they are never in the Seasons or Seasons Web jars, and `runServer` for manual testing doesn't install them.

## Consequences

- The bots can drive a 26.3 server today.
- Bots see the world through a protocol translation, so a scenario that depends on a 26.2 or 26.3 feature the 26.1 protocol can't express (new blocks, items or packets) may need checking by hand or with a newer Mineflayer.
- Through the translation, Mineflayer's position packets get a bot kicked with "Invalid move player packet" within a second or two of joining. Bots therefore join standing still and send no movement packets unless a scenario asks for physics (`ctx.join(name, { physics: true })`). Scenarios that walk, fly or throw pearls (M2 and M3) need this looked at again, ideally with a Mineflayer that speaks the server's version.
- When Mineflayer supports the newest supported drop, set `botClientVersion` in `testkit/build.gradle.kts` to it and remove both plugins.
- M1 part 2 checked movement again (A5, A6, A15). Server-side teleports work: a standing-still bot is moved, and `/data get entity <bot> Pos` shows the new position. A bot's own block placement does not: through ViaBackwards the server leaves the block as air, with or without the look packet that goes with it. A15 therefore places blocks with the test plugin's `/scenario place`, which fires the same `BlockPlaceEvent` (ADR 0003).
- M2 part 1 (2026-11-18): Mineflayer is still 4.39.0, so bots still don't move. A27's walking, being pushed and falling are made by the test plugin's `/scenario move`, which fires `PlayerMoveEvent` from where the player stands and then puts them there, as the server would apply client movement; Seasons sees the same event either way. Pearls are real ones thrown by the test plugin with the bot as thrower. Gliding is set by the test plugin and checked in the same tick, since a still bot never tells the server it is in the air.
