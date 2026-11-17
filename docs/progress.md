# Progress

Where each milestone stands. A milestone is done when its acceptance scenarios pass in CI (see Milestones in `docs/spec.md`).

| Milestone | State | Scenarios passing |
| --- | --- | --- |
| M0 Skeleton | Done (#1, review fixes #7) | Empty scenario, NBT neutrality |
| M1 Core rollover | Part 1 done (#8); part 2 in review (#9, branch `m1-archiving`); part 3 next | Whole: A1, A2, A7, A8, A9, A10, A40. Part 2's half only: A4 and A5 (no cells yet), A6 (no zone lock until M3), A11 (no displays yet), A12 (no plaque yet), A15 (no Hall yet), A47. A13 and A14 moved to M4 (pending confirmation) |
| M2 to M7 | Not started | — |

## M1

**Part 1: season state** (#8). Passing end to end on Paper 26.3:

| Scenario | Test |
| --- | --- |
| A1 reminders at 7d, 1d, 1h, 10m in chat and on Discord; nothing ends at the planned time | `testkit/bots/scenarios/a01-reminders.mjs`; unit: `RemindersTest`, `SeasonsTest#remindersGoToChatAndDiscordOnceEach`, `DiscordWebhooksTest` |
| A2 10-minute boss-bar countdown, cancel, `--now`, second end refused | `a02-end-countdown.mjs`; unit: `SeasonMachineTest`, `SeasonsTest#theCountdownShowsABar…`, `#endNowSkipsTheCountdown` |
| A40 broken YAML reload keeps the old config and reports file, line, message, to whoever ran it, the other online admins and the console | `a40-reload.mjs` (a second op bot, and the server log); unit: `ConfigLoaderTest`, `SeasonsTest#reloadSwapsEverythingOrNothing`, `#aFailedReloadAlsoReachesTheOtherAdminsAndTheLog` |
| A47 (first half) `/season init 2` once, a second init refused | `a47-init.mjs`; unit: `SeasonMachineTest`, `SeasonsTest#initRecordsTheSeasonOnce…` |
| State survives a restart; a countdown is cancelled by one; reminders aren't resent | `m1-state-restart.mjs` (two sessions); unit: `SeasonsTest#aRestart…`, `SeasonStoreTest`, `JournalTest`, `JournalRecoveryTest` |

Built: the state machine (Active with Frontier and Open, Ending as countdown then archiving, Intermission, the Launch and Homecoming transitions), `season.json`, the journal and recovery framework, config records with validation and atomic reload, `messages.yml`, `/season init`, `schedule end`, `end`, `cancel`, `status`, `info`, `reload`, `validate`, the Discord reminder sender. `/season end` reaches the Intermission through `RolloverArchiver`, wired to `StubArchiver`, which moves nothing.

**Part 2: archiving** (#9, branch `m1-archiving`). Passing end to end on Paper 26.3:

| Scenario | Test |
| --- | --- |
| A4 (part 2's half) end state: everyone online archived (inventory, ender chest, XP, advancements, vault), then cleared and reset; "in their own cell" waits for part 3 | `a04-end-state.mjs`; unit: `ArchivingTest#theEndArchivesEveryoneOnlineIntoTheSeason`, `#archivingAgainTakesNothing` |
| A5 (part 2's half) catch-up: an offline player is archived at login, then enters the new season after launch; "into their cell" waits for part 3 | `a05-catch-up.mjs`; unit: `ArchivingTest#anOfflinePlayerIsArchivedAtLogin…`, `LoginPlanTest` |
| A6 (part 2's half) `/season launch`: refused without a spawn or while anyone online isn't archived, `--dry-run`, everyone online teleported to the new spawn with title, sound and fireworks, deaths objective, announcement; locking the old-world zones waits for M3 | `a06-launch.mjs`; unit: `SeasonsTest`, `LoginPlanTest` |
| A7 a crash at each step (file written, `ARCHIVE_STARTED`, `VAULT_SEALED`, reset, `ARCHIVE_DONE`) ends with every item exactly once | `a07-crash-*.mjs` (five, two sessions each); unit: `ArchivingTest#aCrashAtAnyStepEndsWithEveryItemExactlyOnce`, `#recoveryStopsIfTheInventory…`, `JournalRecoveryTest` |
| A8 the archive view: paged, view only, nothing can be taken by any click type, the off-hand swap included | `a08-archive-view.mjs` (opened through the scenario hooks, ADR 0003); unit: `ArchivePagesTest`, `PaperMenusTest` |
| A9 tripwire: an archived item found in the world is deleted, audited and logged | `a09-tripwire.mjs`; unit: `PaperPortsTest` |
| A10 archive files are NBT neutral | `NbtNeutralityTest` (archive samples from the testkit) |
| A11 (part 2's half) `/season unarchive` restores items and advancements, refused over a full inventory, `--force` keeps what was in the way; removing the displays waits for part 3 | `a11-unarchive.mjs`; unit: `ArchivingTest#unarchiveRestoresAndKeeps…` |
| A12 (part 2's half) advancements archived with the completed / visible count, the screen empty afterwards, restored without announcements; the plaque waits for part 3 | `a12-advancements.mjs` |
| A15 (part 2's half) the furthest block placed from 0,0, in `/season status`; "placing one in the Hall doesn't count" waits for the Hall in part 3 | `a15-furthest-block.mjs`; unit: `FurthestBlockTest`, `ArchivingTest#theFurthestBlockCounts…` |
| A47 (second half) players from before Seasons are archived into the init season | `ArchivingTest#aPlayerFromBeforeSeasons…`, `m1-archive-commands.mjs` |
| `/season archive`, `spawn set`, `reprocess` | `m1-archive-commands.mjs` (two sessions) |
| Review fixes: nothing leaves the player before the clear (cursor and grid), archive files never overwritten, unarchive never re-archived into an older season, launch waits for unarchived players, players held at login | unit: `ArchivingTest#aCrashAtAnyStep…` (with a cursor item), `#aFailedWriteLeavesEverything…`, `#aJoiningPlayerIsHeld…`, `#unarchivingDuringALaterSeason…`, `#unarchivingAnOlderSeason…`, `#anArchiveFileIsMovedAside…`, `PaperPortsTest#settling…` |

A13 (firsts) and A14 (season card): moved to M4 (pending confirmation), see `docs/questions.md` 27. Not failing; not part of M1.

Built: `Archiver` and `Unarchiver` with journal recovery at every step, archive and vault files, `/season launch`, `spawn set`, `archive`, `unarchive`, `reprocess`, login catch-up, season entry (safe teleport, immunity, celebration, statistics baseline), the furthest-block tracker, the paged archive view, a minimal tripwire (pickup and inventory-close checks, a 30-second sweep; delete, audit, warn; the Discord webhook waits for M4), and the scenario hooks (ADR 0003). `StubArchiver` is gone.

Movement spike (folded into A5, A6, A15): server-side teleports of bots work through ViaBackwards; a bot's own block placement is dropped, so A15 places blocks with the test plugin's `/scenario place`, which fires the same `BlockPlaceEvent` (ADR 0001, ADR 0003).

Part 3 seams, empty until then: `Galleries.lockAll`, `CellDirectory` (returns no cells, which is why archived players "stay"), `DisplayRenderer`, `HallBounds`.

**Part 3: the Hall** (next). Cells (random assignment, swaps, tagging by command), figure displays, the plaque, archive anchors, legacy import. Scenarios A3, A20, A21, A46, A50, A51, and the Hall halves of A4, A5, A11, A12, A15 and A47. A6's zone lock comes with zones in M3. A8 switches from the scenario hook to clicking an archive anchor (the TODO in `a08-archive-view.mjs`).

Known limits: test bots stand still and send no movement packets, and can't place blocks themselves (ADR 0001); M2/M3's walking, flying and pearl scenarios need a Mineflayer that speaks the server's version.
