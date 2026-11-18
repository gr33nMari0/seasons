# 3. Scenario hooks, only on scenario servers

Date: 2026-11-18 · Status: accepted

## Context

The M1 acceptance scenarios need three things no Seasons command offers:

- A7 stops the server dead at each step of archiving, to show every item ends up exactly once after the restart.
- Several scenarios check what an archive holds (stacks, items, the vault page, the advancement count) and whether a player's operations wait for recovery.
- A8 clicks through the archive view, which players reach through archive anchors in the Hall. The Hall arrives in M1 part 3.

## Decision

`com.petepeterepeat.seasons.core.scenario.ScenarioHooks` does exactly three things:

1. Arm a crash point for one player: archiving them calls `Runtime.getRuntime().halt(0)` right after that step's fsync (the archive file written, `ARCHIVE_STARTED`, `VAULT_SEALED`, the reset, `ARCHIVE_DONE`).
2. Read an archive summary and the player's record (`archivedThrough`, operations pending).
3. Open an archive view for a viewer.

Seasons creates the hooks only when the JVM starts with `-Dseasons.scenario=true`, read once in `SeasonsPlugin#onEnable`. Without the flag no `ScenarioHooks` object exists, no service is registered, nothing listens for crash points (the archiver gets `CrashPoints.NONE`) and no command reaches them. Nothing can turn them on at run time: there is no command, config key or API call for it. Only the scenario servers set the flag, in `testkit/build.gradle.kts`, and Seasons logs a warning when it is on.

The test plugin (`testkit`, never shipped) finds the hooks through the services manager and offers `/scenario crash-at`, `/scenario archive` and `/scenario open-archive` to the admin bot. When the server halts on purpose, the test plugin writes `halted.json`; Gradle then accepts the session if the bot runner's own `results.json` says every check passed.

## Consequences

- A production server is unaffected: the hooks are inert unless an operator adds the JVM flag by hand.
- The test plugin also has `/scenario place`, which fires a `BlockPlaceEvent` for a bot and sets the block if nothing cancels it. It is not one of the hooks and touches nothing in Seasons: through ViaBackwards the server drops a Mineflayer bot's own block placement, so A15 can't place blocks otherwise (see ADR 0001).
- Opening the archive view through the hooks is temporary. M1 part 3 switches A8 to clicking an archive anchor (the TODO in `a08-archive-view.mjs`).
- M1 part 3 amendment (2026-11-18): crash points also cover `/hof import` (`IMPORT_STARTED`: the record written and journalled, nothing in the cell changed; `IMPORT_CLEARED`: the cell emptied, `IMPORT_DONE` not written), armed for the cell's owner. The archive summary also reports how many pages the archive view has. Like `/scenario place`, the test plugin gains `/scenario break` (fires `BlockBreakEvent`) and `/scenario use` (fires `PlayerInteractEvent` for a right-click on a block), because the translation drops a bot's own clicks on blocks; a spike showed its clicks on entities get through, so figures are clicked by the bot itself. With archive anchors in place, A8 opens the view by clicking one, and `/scenario open-archive` stays only for debugging.
- M1 part 4 amendment (2026-11-18): reading what Seasons holds also covers a player's season card, as plain lines (`/scenario card <player> <season>`), since bots can't read a book's pages through the protocol translation. A14 checks the book itself opens from the plaque anchor by waiting for the `open_book` packet.
- Alternatives rejected: a config key could be switched on by mistake or by `/season reload`; reflection into Seasons from the test plugin breaks the public-API rule and hides what the tests depend on; killing the server process from outside can't stop it at an exact journal step.
- M2 part 1 amendment (2026-11-18): like `/scenario place`, the test plugin gains stand-ins for what bots can't do through the translation, none of them hooks into Seasons: `/scenario use … sneak`, `move`, `pearl`, `use-item`, `consume`, `drop`, `sit` and `glide`. Each fires the real Paper event (or calls the Paper API that fires it) so Seasons decides as it would for a real client.
- Review of #11 amendment (2026-11-18): `/scenario move` puts the player where the move went with a teleport the test plugin cancels at `LOWEST` and lets through at `HIGHEST`, so no other plugin, Seasons included, sees a teleport event, as with a real client's movement. Before this, Seasons also saw the teleport, which by itself returned a visitor, so A27 would have passed without `PlayerMoveEvent` reaching Seasons. A last word `back` makes a second move return the player in the same tick, which only the move event can notice (the Hall's sweep runs every 500 ms); A27 uses it to show the move event is wired.
- M2 part 2 amendment (2026-11-18): three more stand-ins, again none of them hooks into Seasons. `/scenario open` right-clicks a block and, if the click goes through, opens a container's inventory as the game would (`InventoryOpenEvent`), then reports a few ticks later whether the player has the block's own inventory open, something else (Seasons' view-only copy) or nothing. `place-entity` fires `EntityPlaceEvent` for an entity such as a cushion and adds it if allowed. `hit` makes the player attack an entity with `Player#attack`, a real hit with all its events. `/scenario place` now sets the block before `BlockPlaceEvent` runs and puts the old one back if refused, as the game does, so listeners see the placed block. Bots still right-click and hit item frames and armour stands themselves, since entity clicks get through the translation.
- Review of #12 amendment (2026-11-18): `/scenario place` takes a block with its state, such as `minecraft:chest[facing=north,type=right]`, so a scenario can place half of a double chest; `/scenario break … drops` breaks as survival does, dropping what the block drops unless a listener turns drops off, so a scenario can see that Seasons did. Neither hooks into Seasons.
