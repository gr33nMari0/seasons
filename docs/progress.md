# Progress

Where each milestone stands. A milestone is done when its acceptance scenarios pass in CI (see Milestones in `docs/spec.md`).

| Milestone | State | Scenarios passing |
| --- | --- | --- |
| M0 Skeleton | Done (#1, review fixes #7) | Empty scenario, NBT neutrality |
| M1 Core rollover | Part 1 of 2 in review (#8, branch `m1-season-state`) | A1, A2, A40; A47 first half |
| M2 to M7 | Not started | — |

## M1

**Part 1: season state** (#8). Passing end to end on Paper 26.3:

| Scenario | Test |
| --- | --- |
| A1 reminders at 7d, 1d, 1h, 10m in chat and on Discord; nothing ends at the planned time | `testkit/bots/scenarios/a01-reminders.mjs`; unit: `RemindersTest`, `SeasonsTest#remindersGoToChatAndDiscordOnceEach`, `DiscordWebhooksTest` |
| A2 10-minute boss-bar countdown, cancel, `--now`, second end refused | `a02-end-countdown.mjs`; unit: `SeasonMachineTest`, `SeasonsTest#theCountdownShowsABar…`, `#endNowSkipsTheCountdown` |
| A40 broken YAML reload keeps the old config and reports file, line, message | `a40-reload.mjs`; unit: `ConfigLoaderTest`, `SeasonsTest#reloadSwapsEverythingOrNothing` |
| A47 (first half) `/season init 2` once, a second init refused | `a47-init.mjs`; unit: `SeasonMachineTest`, `SeasonsTest#initRecordsTheSeasonOnce…` |
| State survives a restart; a countdown is cancelled by one; reminders aren't resent | `m1-state-restart.mjs` (two sessions); unit: `SeasonsTest#aRestart…`, `SeasonStoreTest`, `JournalTest`, `JournalRecoveryTest` |

Built: the state machine (Active with Frontier and Open, Ending as countdown then archiving, Intermission, the Launch and Homecoming transitions), `season.json`, the journal and recovery framework, config records with validation and atomic reload, `messages.yml`, `/season init`, `schedule end`, `end`, `cancel`, `status`, `info`, `reload`, `validate`, the Discord reminder sender. `/season end` reaches the Intermission through `RolloverArchiver`, wired to `StubArchiver`, which moves nothing.

**Part 2: archiving** (next). Replace `StubArchiver`; archiving with advancements and its journal recovery handler, furthest-block tracking, figure displays, archive view, teleports and login catch-up, `/season launch`, random cell assignment and swaps, cell tagging by command, legacy import. Scenarios A3 to A15, A20, A21, A46, A47 (second half), A50, A51.

Known limits to pick up: test bots stand still and send no movement packets (see `docs/adr/0001-viaversion-on-scenario-servers.md`), which part 2's teleport and M2/M3's movement scenarios will need to revisit.
