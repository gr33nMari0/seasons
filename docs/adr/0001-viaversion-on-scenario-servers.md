# 1. ViaVersion and ViaBackwards on scenario servers

Date: 2026-11-17 · Status: accepted

## Context

The acceptance scenarios use Mineflayer bots as players. Mineflayer 4.39.0, the newest release at M0, speaks Minecraft protocols up to 26.1, while Seasons v1 targets 26.3 only. A 26.1 client can't join a 26.3 server.

## Decision

The scenario servers (`testkit`, `scenarioServer_*` tasks) download ViaVersion and ViaBackwards from Modrinth through run-paper, pinned in `gradle/libs.versions.toml`. The bots connect as 26.1 clients and ViaBackwards translates. Nothing ships with them: they are never in the Seasons or Seasons Web jars, and `runServer` for manual testing doesn't install them.

## Consequences

- The bots can drive a 26.3 server today.
- Bots see the world through a protocol translation, so a scenario that depends on a 26.2 or 26.3 feature the 26.1 protocol can't express (new blocks, items or packets) may need checking by hand or with a newer Mineflayer.
- Through the translation, Mineflayer's position packets get a bot kicked with "Invalid move player packet" within a second or two of joining. Bots therefore join standing still and send no movement packets unless a scenario asks for physics (`ctx.join(name, { physics: true })`). Scenarios that walk, fly or throw pearls (M2 and M3) need this looked at again, ideally with a Mineflayer that speaks the server's version.
- When Mineflayer supports the newest supported drop, set `botClientVersion` in `testkit/build.gradle.kts` to it and remove both plugins.
