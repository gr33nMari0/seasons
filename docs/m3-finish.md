# Finishing M0 to M3 before the first beta

Branch `m3-finish`, one pull request into `main`, a commit per item. The readings each item takes are `docs/questions.md` 165 to 177. Nothing here changes a decision in the spec.

## The audit

Sources: the "Not yet" lines in `docs/progress.md`, `docs/questions.md`, TODOs in the code, and the spec's command, permission and config tables against what is registered and read.

**Finish now** (M0 to M3 scope; neither the milestone list nor `questions.md` puts them later):

| # | Item | Where it stands | Spec | Questions |
| --- | --- | --- | --- | --- |
| 1 | Gallery settings: `assignment`, `access`, `renderer`, `decorating` per gallery | `Gallery` has none of them; everything reads config.yml | Galleries (fields) | 166 |
| 2 | Access mode `bounds` | Refused on load (`ConfigLoader#validate`) | Access and decorating | 80, 168 |
| 3 | Access mode `gate` and the gate anchor, `cells.gateBlock` | Refused on load; `AnchorType.GATE` is stored but does nothing | Anchors, Access and decorating | 80, 169 |
| 4 | `/hof claim [cell]` and `claim` assignment | No command; `claim` runs as `manual`; `seasons.hof.claim` is declared but unused | Assignment, Commands | 38, 167 |
| 5 | The armour-stand figure | Mannequins only (`PaperDisplays`); `Capability.MANNEQUINS` is listed but nothing falls back | Displays | 170 |
| 6 | A figure's pose per cell | Not read | Displays | 171 |
| 7 | The launch button | Not built; the runbook's rollover says "Press the launch button" | Season lifecycle (Launch is manual too) | 172 |
| 8 | `/season audit [player]` | Not registered; the audit log is written but can't be read in game | Commands (`seasons.admin.config`) | 173 |
| 9 | "Your archive status" in `/season info` | Season, phase and stage, planned end and cell only | Commands (player) | 174 |
| 10 | Item ids checked in config.yml | `firsts.rareItems` and `cells.containers` aren't checked | Validation rules | 175 |
| 11 | `launch.requireManual` | Parsed, never read | config.yml | 176 |
| 12 | Last valid position in the player record | Kept in memory only (`Zones#lastValid`) | Data model, Season lifecycle | 177 |

**Settled, nothing to build:** `ordered` sorts by name (38); firsts aren't copied into archives until Seasons Web (56); cushions in no-build zones and view-only copies of cell containers came with M3 part 1 and M2 part 2, though progress.md's M1 and M2 "Not yet" lines still listed them; migrations by `schema-version` and `format-version` have nothing to migrate until a version 2 exists, and a newer version is already refused on load.

**On hold**, each placed by the milestone list or `questions.md`: see the On hold list in `docs/progress.md`.

## Plan per item

Every item adds its player-facing text to `messages.yml`, any new setting to the bundled `config.yml` (with its comment) and `SeasonsConfig`, any new Paper call to `docs/paper-api.md`, and any new test-plugin command to ADR 0003. Unit tests run with each commit; only the touched scenarios run locally (`-Pscenarios=`), and the full suite once in the background before pushing.

### 1. Gallery settings

- `Gallery` gains `Settings(Optional<CellAssignment> assignment, Optional<CellAccess> access, Optional<Renderer> renderer, Optional<Decorating> decorating)`; `Cell` gains `Optional<String> pose` (item 6). `GalleryFiles` reads and writes them (absent when unset, so files Seasons wrote before load the same); `GalleryRules` checks values.
- `Galleries#settings(gallery, config)` resolves each against config.yml, and every place that reads `cells.assignment`, `cells.access` or `decorating.lockAtEnd`/`afterArchive` (`HallService`, `Decorating`, `HallRules`, `ConfigLoader#validate`) asks it instead.
- Unit: `GalleryFilesTest#aGallerysOwnSettingsReadAndWriteBack`, `#unsetSettingsComeFromConfigYml`, `#aBadSettingSaysWhere`; `HallRulesTest#aGallerysOwnAfterArchiveDecidesItsOwnersTime`.
- Scenario: none of its own; items 2 to 5 use it.

### 2. Access `bounds`

- `HallVisits`: a cell-edge check beside the Hall-edge one, on moves, vehicle moves and teleports (the same intents `Zones` refuses on), putting the player back where they last stood outside the cell; the 500 ms sweep moves anyone found inside a `bounds` cell they may not be in to the gallery's entry, with the reason. The rule "may be in" is one method on `HallService` (owner while they may decorate, or bypass).
- `Archiver`/`SeasonEntry` cell teleports and `HallVisits` `/hof <player>` choose the gallery's entry for a `bounds` cell the player may not be in.
- `GalleryRules`: every cell of a `bounds` gallery has bounds. `ConfigLoader#validate` no longer refuses `bounds`.
- `HallAudit` `hall-blocked` for refused entries, repeats counted.
- Unit: `HallVisitsTest#aBoundsCellRefusesEveryoneButItsDecoratingOwner`, `#theSweepMovesAnyoneInsideABoundsCellToTheGalleryEntry`, `#anOwnersTimeRunningOutMovesThemOut`, `#hofToAPlayerInABoundsGalleryLandsAtTheGalleryEntry`; `GalleryFilesTest#aBoundsGalleryNeedsCellBounds`; `ConfigLoaderTest#accessModesNotBuiltYetAreRefused` replaced by `#everyAccessModeLoads`.
- Scenario `m2-access-bounds.mjs`: a visitor walks (`/scenario move`), pearls and is `/tp`'d into a cell and is put back; the owner walks in during the window, `/season decorate close` moves them out; `/hof <owner>` lands at the gallery entry; a bypass holder walks in; the refusals are in the audit log.

### 3. Access `gate`

- `SeasonsConfig.Cells` gains `gateBlock` (default `iron_bars`), validated by item 10.
- `GalleryRules`: a cell's gate anchors are two corners of a plane one block thick inside its bounds; a cell without them in a `gate` gallery is a warning.
- New `hall/Gates`: works out each gate's state (open while the owner may decorate, shut otherwise), and on every change of decorating state (window open, owner's time out, decorate close, launch), reload and startup, journals `GATE_SET` (new `JournalKind`, payload gallery, cell, state) and sets the plane through a new `World#setBlocks` on the server thread, chunks loaded as `Reconciler` does. Recovery: nothing beyond the startup pass, which sets every gate to its state. A player standing in a cell when its gate shuts goes to the gallery's entry.
- `HallRules`/`HallGuard`: gate blocks drop nothing; only bypass holders change them. `Reconciler` checks the plane and puts back a changed gate (audited `reconcile`).
- Paper: `PaperWorld#setBlocks` (`Block#setType` with physics, so panes and bars connect).
- Unit (new `GatesTest`): `#aGateIsOpenOnlyWhileItsOwnerMayDecorate`, `#everyChangeIsJournalledFirst`, `#startupSetsEveryGateToItsState`, `#aPlayerInsideWhenTheGateShutsGoesToTheGalleryEntry`; `GalleryFilesTest#aGateIsAPlaneInsideItsCell`; `HallRulesTest#gateBlocksAreHallBlocks`; `HallRulesTest#reconciliationPutsBackAChangedGate`.
- Scenarios `m2-access-gate.mjs` (two sessions): the plane is bars before the window, air once it opens, bars at `/season decorate close` with the owner inside moved out; a visitor can't break it; `/setblock` on it is put back by `/hof reconcile`; after a restart it is still shut. `m1-gate-crash.mjs` (two sessions): a crash point after `GATE_SET` and before the blocks change, and the gate is right after the restart.

### 4. `/hof claim` and `claim` assignment

- `HallCommands`: `/hof claim [cell]` (`seasons.hof.claim`, players only). `HallService#claim` checks, in order: the window, a `claim` gallery for that season, played the season, no cell already, the cell free; with no cell, the free cell they stand in, else the list of free cells. The claim goes through `ConfigEdits` (the gallery file), so concurrent claims are serial and the second is refused as taken; audited `claim`.
- `HallService`: the window's draw skips `claim` galleries and tells players without a cell to claim; archiving gives a random free cell to a player without one (from any of the season's galleries). Question 38's `claim`-as-`manual` branch goes.
- Unit (new `ClaimTest`): `#claimTakesAFreeCellInAClaimGalleryDuringTheWindow`, `#eachClaimRefusal`, `#twoClaimsForOneCellOneWins`, `#noCellAtArchiveTimeGetsARandomFreeOne`, `#claimWithNoCellTakesTheOneTheyStandIn`.
- Scenario `m1-claim.mjs`: a `claim` gallery; the window opens and two players are told to claim; one claims `c1`, the other is refused `c1` and claims by standing in `c2`; claims outside the window and twice are refused; a third player who never claims is archived into the free `c3`; the claims are audited.

### 5. The armour-stand figure

- `port/Displays#figure` takes the renderer; `PaperDisplays` draws an `ArmorStand` (arms, `addEquipmentLock` on every slot, invulnerable, no gravity, silent, the head as a `PLAYER_HEAD` with `SkullMeta#setPlayerProfile` from the archived skin) with the same tags. `HallRenderer` puts the helmet on the first trophy anchor. The core falls back when `Capability.MANNEQUINS` is missing.
- `Reconciler` treats a figure of the wrong kind as missing (draws the right one, removes the old). `PaperEvents`/`HallRules` already cancel interactions with tagged displays; the armour stand's manipulate event joins them.
- Unit (new `FiguresTest`): `#anArmourStandGalleryDrawsTheFallbackWithTheHelmetOnATrophy`, `#withoutMannequinsFiguresFallBack`, `#aFigureOfTheOtherRendererIsDrawnAgain`; MockBukkit `PaperPortsTest#theArmourStandIsLockedAndWearsTheSkin`.
- Scenario `m1-armour-stand.mjs`: a gallery with `renderer: armour-stand`; after `/season end --now` the cell has an armour stand with the head, the helmet is on the trophy, a visitor can't take anything from the stand (click, hit, `/scenario hit`), the tripwire leaves the copies alone in place, and after editing the renderer to `mannequin` and `/hof reconcile` the stand is a mannequin.

### 6. A figure's pose

- `Cell#pose` (item 1), validated against the poses the Paper API sets on a mannequin; `PaperDisplays` sets it. A pose on an armour-stand figure is a warning.
- Unit: `GalleryFilesTest#aCellsPoseReadsAndABadOneSaysWhere`; MockBukkit `PaperPortsTest#aMannequinTakesItsCellsPose`.
- Scenario: `m1-armour-stand.mjs` also checks a mannequin cell drawn crouching (the bot reads the entity's pose).

### 7. The launch button

- `Gallery#launch` (an optional position in the gallery file), `/hof gallery launch <gallery> [at|none]`, validated inside the gallery's bounds.
- `HallVisits`/`HallRules`: right-clicking the block is a new `Intent.UseLaunch`; with `seasons.admin.season` during the Intermission the first press asks for a second within 10 s, the second runs the same path as `/season launch` (refusals included) as that player; anyone else is refused with a message and audited.
- Unit (new `LaunchButtonTest`): `#twoPressesWithin10SecondsLaunch`, `#oneThatTimesOutDoesNothing`, `#aPlayerWithoutTheNodeIsRefusedAndAudited`, `#outsideTheIntermissionItSaysWhy`, `#launchsRefusalsStillApply`.
- Scenario `m1-launch-button.mjs`: a non-op presses it and nothing happens; the op presses once, waits past 10 s, presses again and again; everyone is at the new spawn as in A6.

### 8. `/season audit [player]`

- `AuditLog#recent(Optional<String> player, int count)` reads this month's and last month's files off the server thread, tolerating a half-written last line; `SeasonCommands` registers `audit [player]` under `seasons.admin.config` and replies on the server thread.
- Unit (new `AuditLogTest`): `#recentReadsAcrossTheMonthBoundary`, `#filtersByActorOrTargetByNameOrUuid`, `#aBrokenLineIsSkipped`; `SeasonsTest#auditNeedsTheConfigNode`.
- Scenario `m3-audit.mjs`: after a zone edit and an archive, `/season audit` lists both, `/season audit <player>` only theirs, a non-op is refused.

### 9. Archive status in `/season info`

- `SeasonCommands#info` adds one line from the player record and the season state: archived into Season N, waiting to be archived into Season N, or nothing.
- Unit: `SeasonsTest#infoGivesTheArchiveStatus` (three cases).
- Scenario: `m1-archive-commands.mjs` gains `/season info` before and after `/season archive`.

### 10. Item ids in config.yml

- `Platform#knownItem(String)` (Paper: `Registry.ITEM` by key, plus Seasons' groups); `ConfigLoader` takes it and checks `firsts.rareItems`, `cells.containers` and `cells.gateBlock` with their key paths.
- Unit: `ConfigLoaderTest#unknownItemsAreProblemsWithTheirPath`, `#groupsCount`.
- Scenario: `m3-config.mjs` gains `/season config set firsts.rareItems [not_an_item]` refused with the path, the file unchanged.

### 11. `launch.requireManual`

- `ConfigLoader#validate` refuses `false` with a message.
- Unit: `ConfigLoaderTest#requireManualFalseIsRefused`. No scenario: it is a load check, covered with item 10's in `m3-config.mjs` (`set launch.requireManual false` refused).

### 12. Last valid position in the player record

- `PlayerRecord#lastValid` (`format-version` unchanged: an absent field reads as none); `Zones` updates it through `PlayerStore` (coalesced) and seeds its map from the record at login.
- Unit: `ZonesTest#aPlayerFoundInsideAfterARestartGoesBackToTheirSavedLastValidPosition`, `#aSavedPositionNowInsideAZoneSendsThemToTheSpawn`; `SeasonStoreTest#aPlayersLastValidPositionRoundTrips`.
- Scenario: `m3-login-inside.mjs` gains a second session: a player who stood outside the zone, was moved inside by `/tp` and logged out, is back at their last valid spot after the restart; the existing case (no saved spot) still goes to the spawn.

## Order

1, then 2 and 3 (they share the cell edge and decorating state), 4, 5 with 6, 7, then the small ones: 8, 9, 10 with 11, 12. When the branch is done: progress.md gains a section for it with its scenarios, the On hold list loses nothing (it is M4 to M7 only), questions 38 and 80 lose "to be", and this file is deleted.
