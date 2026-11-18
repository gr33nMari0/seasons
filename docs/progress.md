# Progress

Where each milestone stands. A milestone is done when its acceptance scenarios pass in CI (see Milestones in `docs/spec.md`).

| Milestone | State | Scenarios passing |
| --- | --- | --- |
| M0 Skeleton | Done (#1, review fixes #7) | Empty scenario, NBT neutrality |
| M1 Core rollover | Done (#8, #9, #10) | A1, A2, A3, A4, A5, A7, A8, A9, A10, A11, A12, A13, A14, A15, A20, A21, A40, A46, A47, A50, A51. A6 passes except the zone lock, which waits for M3 (questions 53) and runs again in full then. `/season config get|set` comes with M3 (questions 54) |
| M2 Hall protection | Part 1 (visits) done (#11); part 2 (protection) in review (#12) | Passing locally, not yet in CI, so M2 isn't done: A22, A23, A24, A25, A26, A27, A28, A29, and A44 except refusing cushions in a no-build zone, which waits for zones in M3 (as A6's zone lock does). A9 widened |
| M3 Old world | Part 1 (zones and Homecoming) in review (#13) | Passing locally, not yet in CI: A30, A31, A32, A33, A34, A35, A45; A6 now in full (the zone lock); A41's spawn half; A44's no-build half. Part 2 (ender vault, pets, BlueMap areas, spawn candidates, the other shapes) brings A16 to A19, A36 and the rest of A41 |
| M4 to M7 | Not started | — |

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

**Part 2: archiving** (#9). Passing end to end on Paper 26.3:

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

A13 (firsts) and A14 (season card): built in part 4, below (questions 27).

Built: `Archiver` and `Unarchiver` with journal recovery at every step, archive and vault files, `/season launch`, `spawn set`, `archive`, `unarchive`, `reprocess`, login catch-up, season entry (safe teleport, immunity, celebration, statistics baseline), the furthest-block tracker, the paged archive view, a minimal tripwire (pickup and inventory-close checks, a 30-second sweep; delete, audit, warn; the Discord webhook waits for M4), and the scenario hooks (ADR 0003). `StubArchiver` is gone.

Movement spike (folded into A5, A6, A15): server-side teleports of bots work through ViaBackwards; a bot's own block placement is dropped, so A15 places blocks with the test plugin's `/scenario place`, which fires the same `BlockPlaceEvent` (ADR 0001, ADR 0003).

Part 3 seams, empty until then: `Galleries.lockAll`, `CellDirectory` (returns no cells, which is why archived players "stay"), `DisplayRenderer`, `HallBounds`.

**Part 3: the Hall** (#10). Passing end to end on Paper 26.3, with `./gradlew build scenarioTest` green:

| Scenario | Test |
| --- | --- |
| A3 cells lock at the end of the countdown, before anyone is archived: owners in their cell switch to adventure first | `a03-lock-before-archive.mjs` |
| A4 (Hall half) everyone ends in their own cell | `a04-end-state.mjs` |
| A5 (Hall half) a player archived at login in the Intermission lands in their cell, figure drawn | `a05-catch-up.mjs` |
| A8 the archive view opens from the archive anchor | `a08-archive-view.mjs` (`/scenario use`; the hook is gone from it) |
| A11 (Hall half) unarchive removes the figure and plaque; archiving again draws them | `a11-unarchive.mjs` |
| A12 (Hall half) the plaque shows "completed / visible" | `a12-advancements.mjs` |
| A15 (Hall half) blocks placed in the Hall don't count | `a15-furthest-block.mjs` |
| A20 random assignment when the window opens (by itself 7 days before the planned end), and at login for late players | `a20-assignment.mjs` |
| A21 `/hof swap` by both within 60 s; refused outside the window and with yourself | `a21-swap.mjs` |
| A46 `decorating.afterArchive`: owners build in their own cell only, until it locks; launch locks at once; building outside the Hall is untouched | `a46-after-archive.mjs`, `a46-launch-locks.mjs`; the window survives a restart and `/season decorate close` ends it: `m1-decorating-restart.mjs` (two sessions); unit: `SeasonStoreTest#theDecoratingWindowRoundTrips` |
| A47 (Hall half) Season 1 imported, then Season 2's gallery filled by the first rollover | `a47-init.mjs` |
| A50 `/hof import`: dry run report, one record with every stack, a gear page then one page per chest, a mannequin in the head's skin, archive anchors opening their chest's page, trophies; undo puts every item back exactly | `a50-import.mjs`; unit: `ImportPagesTest` |
| A51 a cell with no head or a stranger's head is flagged and skipped until `/hof cell assign` | `a51-import-owner.mjs` |
| An import crash after the record is written, and after the cell is emptied, is finished at startup with every item once | `m1-import-crash-started.mjs`, `m1-import-crash-cleared.mjs` (two sessions each) |
| Galleries and cells by command, their rules, a hand edit picked up by reload, a broken one refused | `m1-hall-commands.mjs`; unit: `GalleryFilesTest` |

Built: gallery files (`galleries/<id>.yml`, validated on load and reload), `/hof gallery create|bounds|entry|exit|info`, `/hof cell new|bounds|anchor|unanchor|assign|unassign|info`, `/hof swap`, `/hof reconcile`, `/hof restore`, `/hof import [cell] [--dry-run]` and `/hof import undo`, `/season decorate open|close`, the decorating window in `season.json`, random assignment, game modes and building rules in the Hall, mannequin figures in the archived skin and gear, plaques, imported trophies, archive anchors and figures opening the archive view at a page, and journalled imports (`IMPORT_*` kinds, crash points `IMPORT_STARTED` and `IMPORT_CLEARED`). Decisions: `docs/questions.md` 36 to 48, and 49 to 54 from the review.

Review fixes: a cell changed between the import's scan and its clear stops that cell and changes nothing; `/season unarchive` refuses imported records; leaving the Hall puts back survival only for players Seasons switched, remembered across restarts; the import never writes a gallery that breaks the rules, and anchors a double chest at a half inside the cell. Unit: `ArchivingTest#aChestEmptiedAfterTheScan…`, `#anImportTakesTheCellsItems…`, `#anImportThatWouldWriteABrokenGallery…`, `#anImportedRecordIsNeverUnarchived`, `#leavingTheHallPutsBackSurvival…`.

Not yet: the wand and `/hof stamp` (M5), access modes `bounds` and `gate`, view-only copies of decorative containers, visiting the Hall (`/hof`, beds), `/hof claim`.

Known limits: test bots stand still and send no movement packets, and can't place blocks themselves (ADR 0001); M2/M3's walking, flying and pearl scenarios need a Mineflayer that speaks the server's version.

**Part 4: season firsts and the season card** (#10). Passing end to end on Paper 26.3:

| Scenario | Test |
| --- | --- |
| A13 the first to an advancement is broadcast and posted to Discord, the second isn't; the Nether and a rare item are firsts too; `/season firsts` lists them | `a13-firsts.mjs`; unit: `FirstsAndCardTest#theFirstToEachMilestone…`, `#nothingIsAFirstOnceTheSeasonHasEnded`, `SeasonStoreTest#seasonFirstsRoundTrip…` |
| A14 the season card: highlights, firsts, furthest block and every General-tab statistic as the season's difference; it opens as a book from the plaque anchor | `a14-season-card.mjs` (card lines through the scenario hooks, ADR 0003); unit: `FirstsAndCardTest#theSeasonCardHas…`, `#theCardOpensFromThePlaqueAnchorAsABook` |
| A broken gallery file at startup is left out alone; status and joining admins are told; end and launch wait for a reload that fixes it | `m1-gallery-left-out.mjs` (two sessions); unit: `FirstsAndCardTest#aBrokenGalleryIsLeftOut…`, `#aGalleryBrokenDuringTheIntermission…` |

Built: season firsts for advancements, the Nether, the End, diamonds, elytras and `firsts.rareItems`, kept in `season.json`, broadcast and posted to Discord once each; `/season firsts`; the season card as a book from the plaque anchor; block counts with the statistics baseline; galleries left out at startup. Decisions: `docs/questions.md` 55 to 61. The dragon slayer first, awards and the egg wall wait for M4.


## M2

**Part 1: visiting the Hall** (#11). Passing end to end on Paper 26.3, with `./gradlew build scenarioTest` green:

| Scenario | Test |
| --- | --- |
| A26 `/hof` within 3 blocks of any bed, a straw bed included, and by sneak-clicking a bed with an empty hand; refused with the reason with a monster near, after damage in the last 10 s, while falling or gliding, in lava, by the rate limit, and on a spot return safety would turn down (magma) | `a26-hof-entry.mjs`; unit: `HallVisitsTest#aBedNearbyTakes…`, `#eachCheckRefusesWithItsReasonInOrder`, `#sneakClickingABed…`, `#aSpotReturnSafetyWouldTurnDown…`, `#aFailedTeleportKeepsNoSavedSpot…` |
| A27 leaving the Hall by walking, being pushed, falling or a pearl returns the player to their saved spot | `a27-sealing.mjs`: a real pearl; walking, pushing and falling are stand-ins (`/scenario move`, ADR 0003) that fire `PlayerMoveEvent` with no teleport event, differing only in where the player goes, since the server sees all three the same way; stepping out and straight back in one tick is caught only by the move event, which shows it is wired. Real client movement is unverified until Mineflayer speaks 26.3 (ADR 0001); unit: `HallVisitsTest#leavingTheBoundsByAnyRouteReturnsThem`, `#seasonsOwnTeleportsOutAreNotLeaving`, `#betweenSeasonsLeavingReturnsToTheHallEntry`, `#walkingInSavesWhereTheyCameFrom` |
| A28 lava on the saved spot: the bed; the bed unsafe too: the nearest safe block within 8; nothing safe: the season spawn | `a28-return-fallbacks.mjs`; unit: `HallVisitsTest#anUnsafeSpotFallsBack…` |
| A29 a creeper at the saved spot sends the player to their bed | `a29-creeper.mjs`; unit: `HallVisitsTest#aCreeperAtTheSavedSpot…` |
| A44 (sitting half) a visitor sits on a cushion in someone else's cell, and on nothing else in the Hall | `a44-cushion-sit.mjs`; unit: `HallVisitsTest#insideNothingIsDropped…` |
| The saved spot survives logging out and a restart; arrival immunity; the game mode they left in comes back | `m2-hof-restart.mjs` (two sessions); unit: `HallVisitsTest#theSavedSpotSurvivesARestart` |
| No drops, pickups, pearls or chorus fruit inside; an exit anchor takes a visitor back; a pearl from outside can't land inside | `m2-hall-sealing.mjs` |

Built: `/hof` (alias `/halloffame`), `/hof <season>`, `/hof <player>`, `/hof leave`, sneak-clicking a bed; the entry checks (`EntryChecks`) with the new `hof.entry` keys `onGround`, `noHazards`, `cooldown` and `rateLimit`; the saved return (position, dimension, yaw, pitch and game mode) in the player record, written before the teleport; return safety (`ReturnSafety`) with the bed, nearby and spawn fallbacks; arrival immunity; sealing (`HallVisits`): leaving the bounds by a move, a teleport or anything the 500 ms sweep notices returns the player, exit anchors, no drops, pickups, pearls, chorus fruit or riding, cushions as seats, and `seasons.hof.bypass`. Decisions: `docs/questions.md` 62 to 75 (73 to 75 from the review: `/hof` refuses a spot return safety would turn down, `/hof leave` only inside the Hall, stale saved spots dropped).

**Part 2: protecting the Hall** (#12). Passing end to end on Paper 26.3 with `./gradlew build scenarioTest` run locally; they count once CI on #12 passes, and A44 is whole only with M3's no-build zones:

| Scenario | Test |
| --- | --- |
| A22 during decorating the owner places a chest in their cell and opens the real one (and puts diamonds in by shift-click); another player sees a view-only copy and every click takes nothing; a chest with something in it doesn't break, an emptied one does | `a22-cell-container.mjs`; unit: `HallRulesTest#onlyTheDecoratingOwnerOpensTheRealContainerAndEveryoneElseACopy`, `#onlyListedContainersGoInACellAndOnlyEmptyOnesBreak` |
| A23 no hopper, dropper, dispenser, crafter or hopper minecart can be placed in the Hall, by an owner decorating either; a hopper and a hopper minecart under Hall chests pull nothing, while a hopper outside the Hall does | `a23-no-hoppers.mjs`; unit: `HallRulesTest#noHopperDropperDispenserCrafterOrEnderChestGoesInTheHall` |
| A24 after the lock the owner's chest opens as a copy for the owner and everyone else, and still does after a restart | `a24-lock-view-only.mjs` (two sessions); unit: `HallRulesTest#onlyTheDecoratingOwnerOpensTheRealContainer…` |
| A25 a visitor walks into another's cell in adventure mode and can't break, place, take from a chest, pot, item frame or armour stand, pull a lever, open a lectern or edit a sign; a fence gate opens; in creative mode too; each refusal is audited | `a25-walk-in.mjs` (walking by the `/scenario move` stand-in, ADR 0003; frames and stands clicked and hit by the bot itself); unit: `HallRulesTest#visitorsOpenDoorsAndUseNothingElse`, `#entitiesAreDecorationOnlyForTheirDecoratingOwner`, `#refusalsAreAuditedOnceARunWithTheCountAfter` |
| A44 (building half) a visitor can't place or break a cushion in someone else's cell but can sit on one; the owner places and breaks them while decorating, and not once locked | `a44-cushion-build.mjs`; `a44-cushion-sit.mjs` stays; unit: `HallRulesTest#entitiesAreDecorationOnlyForTheirDecoratingOwner` |
| A9 (widened) archived copies in an item frame, on an armour stand and in a Hall chest are deleted and audited when their chunk loads | `a09-tripwire.mjs` |
| The world's side: TNT outside and inside the Hall breaks no Hall block, frame or stand and hurts nobody; fire, a piston, flowing water and a dispenser can't cross the edge, each with a control outside that does change | `m2-hall-world-rules.mjs`; unit: `HallRulesTest#nothingCrossesIntoTheHallByItself`, `#noLavaAnywhereAndWaterOnlyForTheOwner`; MockBukkit: `PaperHallGuardTest` |
| Reconciliation: when a cell's chunk loads again, a figure moved out of its cell is drawn again and the moved one removed, and a stray display removed; `/hof reconcile` puts back changed gear and then finds nothing to do; each repair is audited | `m2-reconcile.mjs`; unit: `HallRulesTest#reconciliationOnChunkLoad…`, `#reconciliationFixesAFiguresGear`, `#reconciliationStaysWithinItsBudgetEachTick`, `#aDisplayFarFromItsCellIsRemovedWhenItsChunkLoads` |
| After their archive (`decorating.afterArchive` at its default 15 minutes) an owner still builds, but their stash opens as a copy, a broken block drops nothing and they carry nothing (questions 87) | `m2-after-archive-keeps.mjs`; unit: `HallRulesTest#afterTheirArchiveAnOwnerArrangesTheirCellButTakesNothingOut` |
| A double chest can't be made across the Hall's edge or a cell's; one already across the Hall's edge opens as a copy from outside and feeds no hopper, while the same pair outside the Hall does (questions 88) | `m2-double-chest.mjs`; unit: `HallRulesTest#aDoubleChestNeverReachesAcrossACellOrTheHallsEdge` |
| `cells.access: bounds` or `gate` is refused on load | unit: `ConfigLoaderTest#accessModesNotBuiltYetAreRefused` |

Built: `HallRules` (deny by default for every player intent in the Hall, and `HallGuard` for changes nobody makes), `HallAudit` (`hall-blocked` and `hall-bypass` entries, repeats within 10 s counted), the `Reconciler` (on entities loading, every 5 minutes for loaded cells, and `/hof reconcile`; about 1 ms a tick), and on Paper `PaperHallGuard` plus the new intents in `PaperEvents`: buckets, fire, bone meal, entity placing, using and breaking, container opens swapped for view-only copies, no damage in the Hall but from `/kill` and the void, and the tripwire over entities and Hall containers as chunks load and over hoppers. Decorating owners pick up in their own cell before the season ends; after their archive they take nothing out of it. Double chests stay on one side of every cell's edge and the Hall's (`PaperChests`). The test plugin gains `/scenario open`, `place-entity` and `hit`, a block state for `place` and `drops` for `break` (ADR 0003). Decisions: `docs/questions.md` 76 to 86, and 87 to 89 from the review.

Not yet: access modes `bounds` and `gate` (refused on load until built), the admin webhook for tripwire hits (M4), cushions in no-build zones (M3).


## M3

**Part 1: zones and Homecoming** (#13). Passing end to end on Paper 26.3 with `./gradlew build scenarioTest` run locally; they count once CI on the pull request passes:

| Scenario | Test |
| --- | --- |
| A30 no walking, flying, riding, pearl, chorus fruit or portal into a locked base zone, and no new portal made inside it; put back where they stood, dismounted and not gliding, with a title and action bar; a bypass holder walks in | `a30-no-entry.mjs` (walking and flying by `/scenario move`, riding a boat by `ride`, chorus fruit and portals by `chorus`, `portal` and `portal-pair`, ADR 0003; a real pearl); unit: `ZonesTest#everyRouteIntoABlockedZoneIsRefusedWithAMessage`, `#bypassHoldersGoWhereTheyLike` |
| A31 a flight 40,000 blocks from spawn isn't stopped | `a31-far-flight.mjs`; unit: `ZonesTest#nothingCrossesABlockedOrNoBuildZonesEdgeByItself` |
| A32 the nether roof is open above `old-nether` drawn with `--max-y 127`: walking and building at y 128; refused below | `a32-nether-roof.mjs`; unit: `ZoneShapesTest#maxYIsInclusiveSoTheNetherRoofStaysOpen` |
| A33 `/season unlock homecoming`: every zone to its after-unlock mode (off, and no-build for one) at once, title, broadcast, Discord post, fireworks at the spawn; refused a second time; a restart keeps it and doesn't celebrate again | `a33-homecoming.mjs` (two sessions); unit: `ZonesTest#homecomingSwitchesEveryZoneInOneTick` (no tick passes between the command and every zone's new mode), `#aRestartAfterHomecomingKeepsTheZonesOpenAndDoesNotCelebrateAgain` |
| A34 in a no-build zone: walk in, open a chest and take bread, press a farm's button; no placing, breaking, buckets or lighting TNT; an explosion breaks nothing and sets off no TNT | `a34-no-build.mjs`; unit: `ZonesTest#noBuildRefusesBuildingButNotUsing`; MockBukkit: `PaperZoneGuardTest#anExplosionBreaksNothingInside` |
| A35 from the edge of a blocked zone: no breaking, placing, opening, buckets or levers inside; pistons, hoppers both ways, water, lava and TNT don't cross, each with a control outside that does change; a bypass holder may | `a35-edge.mjs`; unit: `ZonesTest#nothingInsideABlockedZoneIsTouchedFromOutsideItsEdge`, `#nothingCrossesABlockedOrNoBuildZonesEdgeByItself`; MockBukkit: `PaperZoneGuardTest` |
| A45 every route into the old-world circle refused before Homecoming; after it, a stack of Season 1 bricks taken from an old chest and built with, and part of an old wall broken and rebuilt | `a45-homecoming-build.mjs` (`/scenario place … consume` takes the brick from the stack) |
| A6 (zone half) a zone drawn in the Intermission stops nobody; launch locks it | `a06-launch.mjs` |
| A41 (spawn half) `/season zone add` and `/season spawn set` refuse a spawn in a zone; a hand edit loads with a warning; `/season end --dry-run` names the zone; launch is refused until the zone goes | `a41-dry-run.mjs`; unit: `ConfigLoaderTest#aSpawnInsideAZoneLoadsWithAWarningAndABrokenZoneDoesNot`, `ZonesTest#zoneCommandsEditTheLiveSeasonsFileAndRefuseAZoneAroundTheSpawn` |
| A44 (no-build half) no cushion placed or broken in a no-build zone | `a34-no-build.mjs` |
| The zone commands: add, list, mode, after, remove, each refusal, `/season unlock` and `lock` one zone, the season file, a hand edit reloaded, a broken zone refused | `m3-zone-commands.mjs`; unit: `ZoneFilesTest`, `ZonesTest#oneZoneUnlocksAndLocksAgainOnItsOwn` |
| Logging in inside a blocked zone sends a player to the spawn; a zone drawn around someone online puts them back outside | `m3-login-inside.mjs`; unit: `ZonesTest#aPlayerFoundInsideWithNowhereOutsideToGoIsSentToTheSpawn`, `#theSweepPutsPlayersBackWhereTheyLastStoodOutside`, `#theSweepChecksATenthOfThePlayersEachRun` |
| Announce mode: once per player per zone per day, to chat and Discord | `m3-announce.mjs`; unit: `ZonesTest#enteringAnAnnounceZoneIsBroadcastOncePerDay` |
| Unlock conditions: a zone opens on its date; Homecoming comes with the dragon's death | `m3-unlock-conditions.mjs`; unit: `UnlockConditionsTest`, `ZonesTest#conditionsUnlockZonesAndBringHomecoming`, `SeasonStoreTest#zonesUnlockedOnTheirOwnAndMilestonesRoundTripAndEndWithTheSeason` |

Built: the zone model (`zone/`: `Zone`, `ZoneShape` box and cylinder, `ZoneMode`, `UnlockCondition`, `ZoneFiles`) in each season's file with `homecoming.unlock`; `ZoneState` in `season.json` (zones unlocked or locked on their own, the dragon's death, players who reached the End); `Zones`, which works out the zones in force from the files and the state (indexed by chunk), refuses entry on moves, teleports, portals and vehicle moves, runs the sweep (every 50 ms, a tenth of the players each time), refuses changes across a blocked zone's edge and building in no-build zones, announces entries, checks unlock conditions once a second and runs Homecoming; `ZoneCommands` (`/season zone add|mode|after|remove|list`, `/season unlock homecoming|<zone>`, `/season lock <zone>`); spawn checks in `/season spawn set`, `/season end --dry-run`, launch and on load; `zones.announceToDiscord` in config.yml; and on Paper `PaperZoneGuard` (a listener of its own beside the Hall's, rather than one guard for both: the Hall stops everything inside it, zones only what crosses an edge) with portal, vehicle and dragon events in `PaperEvents`. The test plugin gains `move … fly`, `place … consume`, `ride`, `chorus`, `portal`, `portal-pair`, `bucket`, `ignite` and `permit`, and the bots read Paper's action bar (ADR 0003). Decisions: `docs/questions.md` 90 to 108.

Not yet (part 2): the ender vault (A36), pets and their travel (A16 to A19), BlueMap areas and spawn candidates, the `outside`, `y-above` and `y-below` shapes (refused on load until then), the 24,000-block spawn warning and spawn candidates in A41, `/season config get|set` and markers.
