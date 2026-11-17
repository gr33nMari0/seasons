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
- Opening the archive view through the hooks is temporary. M1 part 3 switches A8 to clicking an archive anchor (the TODO in `a08-archive-view.mjs`).
- Alternatives rejected: a config key could be switched on by mistake or by `/season reload`; reflection into Seasons from the test plugin breaks the public-API rule and hides what the tests depend on; killing the server process from outside can't stop it at an exact journal step.
