# Security review

Reviewed on 2026-11-22, at `a894c6a` (main after #27), as an attacker would: a survival player with no permissions, an alt or a friend to help, a client that can send any packet, and patience. Measured against `docs/spec.md`, Security and integrity, and Web companion security.

Scope: the Paper adapter, core, integrations and the plugin descriptor. Seasons Web is a stub so far (M6 and M7 not started), so its controls are listed at the end as checks for when it is built.

## Summary

| Id | Severity | Finding |
| --- | --- | --- |
| H1 | High | Items in an anvil, stonecutter or villager trade slot escape archiving and travel into the new season |
| H2 | High | A player whose archive fails or finds them busy is left free, unarchived, with everything they carry |
| H3 | High | After a failed archive the ender chest exists twice: sealed in the vault and still in the game |
| M1 | Medium | The archive hold doesn't stop mending, item wear or other plugins' commands from changing the inventory |
| M2 | Medium | Vault click spam floods the disk, the journal and the shared writer, and keeps the player held |
| M3 | Medium | The tripwire can't see inside shulker boxes or bundles, and placing a tagged shulker box unpacks real items |
| L1 | Low | Any teleport during a Seasons teleport's chunk load skips the zone and Hall checks |
| L2 | Low | Return fallbacks (bed, nearest safe spot) and bed respawns can put a player inside a blocked zone |
| L3 | Low | Redstone power and non-player entities cross zone edges |
| L4 | Low | Protection relies on cancelling early; a later plugin can undo it |
| L5 | Low | A power cut can revert the player file after the journal says the archive is done |
| L6 | Low | A curator's actions use the permissions they held when the menu opened |
| L7 | Low | A registered pet that wanders into a locked zone is pulled out at travel |
| I1–I4 | Info | Design trade-offs and deferred items |

H1 is deterministic and needs nothing but a full inventory. H2 and H3 need a trigger, and M1 hands over a reliable one (XP orbs and a mending item), so the three chain together.

## High

### H1. Workstation inputs escape archiving

**Where.** `PaperInventories.settle` (`adapters/paper/.../PaperInventories.java:126`) and `clear` (`:158`).

**What.** Archiving step 1 is "close any open menu and return cursor and crafting-grid items". `settle` handles the cursor and a `CraftingInventory`'s grid. If the cursor item doesn't fit, it leaves the menu open so the cursor isn't dropped. Any other menu stays open as well. `loose()` reports only the cursor for it, the hash covers only storage, armour, off hand and ender chest, and `clear()` empties those, then calls `closeInventory()` last. Closing an anvil, stonecutter, smithing table, grindstone, enchanting table, loom, cartography table or a villager's trade screen hands its input slots back to the player's inventory, which is now empty. `finish` then saves the player file with those items in it and marks them archived.

The anvil's two inputs and the stonecutter's one accept any item, shulker boxes included, so that's 54 stacks. A villager's two payment slots accept anything as well.

**Reproduction.**
1. During the 10-minute end countdown, stand at an anvil with two full shulker boxes.
2. Fill every inventory slot (dirt will do), open the anvil, put a shulker box in each input slot, and pick up one more item so it sits on the cursor.
3. Stay like that until the countdown ends and Seasons archives you.
4. You're sent to your cell holding both shulker boxes. The archive view and the season card don't show them. At launch you carry them to the new spawn.

`/season archive <player>` on a player in that state does the same.

**Impact.** The fresh start, the plugin's main promise, can be skipped for two shulker boxes of gear, every season, with no failure or alert.

### H2. A failed or busy archive leaves the player free

**Where.** `Archiver.archive` (`core/.../archive/Archiver.java:200`, `Busy`; `:416–437`, `finish` throws), `SeasonService.runEndSequence` (`core/.../season/SeasonService.java:365–400`), `LoginService.archiveAndMove` (`core/.../player/LoginService.java:187`), `LaunchSequence.refusal` (`:91`).

**What.** At season end each online player's archive returns an `Outcome`. The end sequence ignores it and moves to the Intermission once every future completes.

- `Busy` (the player was already held, such as by a vault withdrawal) comes back without an alert or an audit entry, and nobody retries it.
- `Failed` (the live inventory changed between capture and clear) alerts admins. The hold is released and the player keeps everything. Every later attempt goes through recovery, which finds the inventory "neither matches nor is empty" and fails again.

The player is now unarchived, outside the Hall, during the Intermission. No zone is in force then, and everyone else is sealed in the Hall. They can walk into anyone's base and take what they like. The login path behaves the same way: a failed catch-up leaves them playing where they logged out, and once launch has locked the zones, the sweep sends them to the new spawn with their whole inventory. Launch refuses only while an unsettled player is online, so logging out before launch avoids that check.

**Reproduction (Failed, via M1).**
1. Before the end, damage a mending item and wear it. Stand in a pile of XP orbs (or under an XP farm's output), or glide with an elytra.
2. When the countdown ends, orbs picked up during the archive's disk writes repair the item, so its NBT changes. `finish` throws "inventory changed after it was copied". Admins see `archive.failed`.
3. You're free in the unlocked old world with your full inventory while everyone else is in the Hall.

For the login variant, log out standing on a pile of XP orbs before the end, then log in during the Intermission. The orbs are picked up during catch-up, and the same thing happens.

**Reproduction (Busy, via M2).**
1. Have an item on the vault's Returned page and a full inventory, or, after Homecoming, any item on a season page during the countdown.
2. Click it repeatedly as the countdown runs out. Each click is a transaction that holds you while it runs.
3. If `hold.hold` fails when your archive starts, you aren't archived and no admin is told.

**Impact.** The fresh start is skipped, and other players' locked bases can be looted during the Intermission.

### H3. The ender chest is duplicated after a failed archive

**Where.** `Archiver.archive` seals the vault page (`Archiver.java:222`) before `finish` checks and clears. `Archiver.recover` accepts an empty inventory as already cleared (`:346`). `Vault.withdraw` never checks for an open archive.

**What.** When `finish` fails (H2), the vault page for that season is already on disk, the journal shows `ARCHIVE_STARTED, VAULT_SEALED`, and the real ender chest hasn't been cleared. That leaves two ways to cash it in:

- **Keep failing.** The player keeps the real ender chest and plays on. After the next Homecoming, `/vault` lets them withdraw the sealed copy, because withdrawals don't look at open operations.
- **Empty out, then get "recovered".** During the Intermission, put everything carried and everything in the ender chest into a chest somewhere, then log out and back in, or wait for `/season reprocess`. Recovery sees an empty inventory and marks the archive done, as the spec allows for a crash after the clear. Now the archive and the vault both hold the items, and the real ones sit in your stash.

**Reproduction.**
1. Trigger H2's Failed path with a full ender chest.
2. Empty your inventory and ender chest into a chest in the world during the Intermission, then relog.
3. Recovery reports "finished archiving after VAULT_SEALED". Your vault page shows the ender chest. At Homecoming, withdraw it; the originals are still in your chest.

**Impact.** A repeatable dupe of a full ender chest of items (27 stacks, shulker boxes included).

## Medium

### M1. The archive hold has gaps

**Where.** `IntentRouter.decide` (`core/.../guard/IntentRouter.java:46`) denies intents. The only intents are the events `PaperEvents` turns into one.

**What.** Between capture and clear, the hold blocks clicks, drops, pickups, use and damage. It doesn't block:
- XP orbs (`PlayerPickupExperienceEvent`) and the mending repair they do (`PlayerItemMendEvent`)
- wear that isn't from damage (`PlayerItemDamageEvent`), such as an elytra while gliding
- commands, whether vanilla ones the player may run or another plugin's (`/hat`, `/trash`, sorting, kits)

Any of these changes the hashed inventory, and the window spans several fsyncs on a shared writer. At season end, with every online player queued on the same writer, that's seconds. The arriving hold at login has the same gaps.

**Reproduction.** See H2's Failed path. Wearing a damaged mending item in a stream of orbs fails the archive reliably.

**Impact.** It hands over a reliable trigger for H2 and H3, and it weakens every other transaction that relies on the hold (vault, curation, unarchive).

### M2. Vault click flood

**Where.** `Vault.clicked` and `queue` (`core/.../vault/Vault.java:343`, `:362`), `withdraw` (`:379`).

**What.** Every left or shift click on a shown item queues a full transaction, with no bound per player. With a full inventory, each click journals `VAULT_WITHDRAW` (fsync), rewrites the vault file (fsync), finds no room, rolls back (another rewrite and fsync), journals `VAULT_WITHDRAW_DONE` (fsync), and writes an audit line that isn't aggregated. Nothing is given, so it never ends. The journal is compacted only at launch, `Journal.append` copies a `CopyOnWriteArrayList`, and `JournalRecovery.open` copies and scans the whole journal on the server thread. `Archiver.archive` and `Curation.start` call it for every player. All Seasons writes share one `SerialWriter`, so archive files, the season state and player records wait behind the flood. The player is also held for most of the time, which feeds H2's Busy path.

**Reproduction.**
1. Keep one item on your vault's Returned page (or any page after Homecoming) and fill your inventory.
2. Open `/vault` and send click packets on that slot as fast as Paper's packet limiter allows (about 400 a second).
3. The journal grows by about 800 lines a second, the audit log by 400, the writer queue grows without limit, and the server thread slows on every `recovery.open`.

**Impact.** Any player can deny service to Seasons' persistence, and with it the season end, and can fill the disk.

### M3. Tripwire blind spots

**Where.** `PaperInventories.isArchived` and `purge` (`adapters/paper/.../PaperInventories.java:270`), `PaperMenus.copy`, `PaperEvents.onPlace` (`:300`), `onChunkLoad` (Hall containers only).

**What.** Only the top-level stack gets `seasons:archived`. A copy of a shulker box or bundle holds untagged items. The tripwire checks only top-level stacks in player inventories, Hall containers and entities. It never looks at containers outside the Hall, or at what's inside a container item. Placing a tagged shulker box is allowed anywhere outside the Hall and zones, and opening the placed box gives real, untagged items.

**Reproduction.** This needs a primary escape first, such as H1-like menu bugs or another plugin that moves items on a click (L4). A tagged shulker box copy that reaches the cursor can be dropped into a bundle (the sweep can't see it there) or placed as a block outside the Hall. Breaking it open leaves untagged items, a permanent dupe of archived gear.

**Impact.** The spec's "one missed check is detected and repaired" doesn't hold for container items: one escape becomes a permanent dupe.

## Low

### L1. The Seasons-teleport flag covers other teleports

`PaperPlayers.teleportSafely` adds the player to `seasonsMoving` before an async chunk load (with generation) and removes it when the teleport completes (`PaperPlayers.java:250–273`). Any `PlayerTeleportEvent` in that window, such as an ender pearl landing, counts as `SEASONS`. `Zones.teleported` and `HallVisits` then skip their checks.

*Reproduction.* Throw a pearl at a spot just inside a blocked zone, then run `/hof` before it lands while the Hall's chunks are unloaded. The pearl puts you inside the zone. The Hall teleport follows a moment later. Every interaction inside the zone is still refused, and the sweep would remove you within 10 ticks, so you gain only a look inside.

### L2. Return fallbacks ignore zones

`HallVisits.safeDestination` (`:778–806`) picks the saved spot, then the bed, then the nearest safe spot within 8 blocks, then spawn. None of these is checked against zones, and Seasons teleports skip `Zones.teleported`. The same goes for a vanilla respawn at a bed inside a zone.

*Reproduction.* Stand at the edge of `old-world`, near a bed, and run `/hof`. Have a friend pour lava on that spot, then run `/hof leave`. The nearest safe spot can be inside the zone. The sweep sends you out within 10 ticks, and nothing inside can be used.

### L3. Things that cross zone edges unchecked

The zone guard decides player intents, pistons, liquids, hoppers, dispensers, fire, growth and portals. It doesn't decide:
- Redstone power across the edge: a torch or redstone block placed outside next to a piston, door, dropper or dispenser inside. The piston's own blocks are all inside one zone, so `allows` passes.
- Non-player entities walking or riding out: allays bringing items to a player outside, mobs that picked up loot, minecarts with chests on an old rail line.

*Reproduction.* Next to an old dropper two blocks inside `old-world`, facing out, place a redstone torch outside the edge. The dropper fires into a block that's still inside, and the item drifts across the edge, where you pick it up. Value depends on old builds near the edge.

### L4. Early cancellation can be undone

`PaperMenus` cancels clicks and drags at `LOWEST` and never checks again. The Hall and zone listeners decide at `LOW`, with `ignoreCancelled` on. A plugin that un-cancels a click, or acts on clicks in its own handler, can move archive-view copies; inventory sorters and "move all" hotkeys do this. A plugin that changes `PlayerTeleportEvent#setTo` after `LOW` can choose a destination nobody checked. The tripwire catches top-level copies on close, but not nested ones (M3).

*Reproduction.* Install an inventory-sorting plugin with a "take all" hotkey, open an archive view and press it. Tagged copies reach the inventory until the close sweep deletes them; a shulker box copy's contents survive inside it.

### L5. The journal is fsynced, the player file isn't

`Archiver.finish` clears, calls `saveData`, then journals `ARCHIVE_DONE` with fsync. Paper writes the player file by rename without fsync. After a power cut, not a JVM crash, the journal and vault page survive while the player file can roll back to the full inventory. That leaves the ender chest in two places, and the player starts the new season equipped. No player can trigger this.

### L6. Curation permissions are read once

`Curation.allowed` (`:406`) is read when the choice menu opens, and `chose` acts on that list later, after a typed reason. A curator whose node is revoked in between can still finish the action.

### L7. Registered pets aren't zone-checked at travel

`/pet add` refuses a pet inside a locked zone, but `Pets.bring` doesn't look again. A registered horse that wanders into an older season's locked zone is teleported out at launch with its saddle and armour.

## Info

- **I1. Creative mode builds in the Hall.** With `hof.creativeBuilds: true` (the default, questions 252), any player in creative mode passes every Hall rule, including opening real cell containers and taking from them. That's fine where only ops have creative. Servers that give a builder rank `/gamemode` should turn it off, and the config comment should say so.
- **I2. `/season reset` from the console or command blocks.** `ResetCommand.operator` treats any non-player sender as allowed, as the spec says ("or the console"). Command blocks need an op to place, so this is noted only.
- **I3. Creative players and the archive view.** A creative client can copy any item it can see, including archive-view copies, with their tag. Creative is trusted anyway, so this is noted only.
- **I4. Seasons Web isn't built.** When it is: bind to localhost only and refuse a non-https `public-url` beyond it; send a strict CSP and `frame-ancestors 'none'`; send `Cache-Control: private, no-store` on logged-in responses; read `proxy.client-ip-header` only from `proxy.trusted`; rate-limit per IP and per account; keep session tokens out of response bodies; audit logins, failed codes and revocations.

## Checked and sound

These held up under the same attack:

- **Archive and vault menus.** Every click type, drags, creative clicks and hopper moves are refused, and the client is resynced. Shown items are decoded copies with the tag. Withdrawals run in the core, never in the menu.
- **Vault transactions.** Remove, persist, give, roll back. The room check comes before `addItem`, so a stack goes in whole or not at all. Recovery rolls back only when the inventory is still what it was before.
- **Unarchive.** It restores the ender chest from the vault page as it is now, so nothing withdrawn comes back twice. It's held throughout.
- **Curation.** It checks the slot's hash before acting, holds the owner and the curator, and journals each step.
- **Dragon egg and museum.** Both are journalled and keyed by a reference.
- **Permission defaults.** `paper-plugin.yml` matches the spec: player nodes `true`, admin and curation nodes `op`, bypass nodes `false`. Every admin subcommand carries its own permission. `/hof` and `/hof leave` check `seasons.hof.visit` at run time.
- **Path handling.** Every file name comes from a slug pattern (galleries, cells, zones, templates), a UUID, an integer season or a generated id. Templates are matched against `[a-z0-9_.-]{1,64}`. `/season config get|set` reaches only bundled keys and refuses secret-looking paths.
- **Text injection.** Placeholders are `Placeholder.unparsed`. BlueMap details are HTML-escaped. Discord posts send `allowed_mentions: {parse: []}`.
- **Test hooks.** Scenario hooks need `-Dseasons.scenario=true`. The testkit plugin fails to load without its properties and isn't in the release.
- **Storage.** Atomic writes fsync the file and the directory. Journal lines carry a CRC. `/hof import` saves the world.
- **Hall and zone guards.** Explosions, pistons, liquids, fire, hoppers, dispensers, growth, endermen, portals and projectiles are all decided.

## Fix plan (High and Medium)

On branch `security-fixes`, one commit per fix, with a pull request into main. Each fix gets unit tests in core, Paper-side tests where the adapter changes, and an end-to-end scenario named `sec-*` (run with `./gradlew scenarioTest -Pscenarios=sec-*`). Each also gets `messages.yml` keys for any new player-facing text, entries in `docs/paper-api.md` for new Paper calls, and a `CHANGELOG.md` line. Where a fix needs a reading of the spec, it goes in `docs/questions.md` (from 285) and no decision changes.

### 1. H1: archive what's in a workstation (`fix(archive): take workstation inputs with the cursor`)

- `PaperInventories.loose` and `settle`: treat the input slots of any top inventory that hands them back on close as loose items, like the crafting grid. That covers anvil, smithing, grindstone, enchanting, loom, stonecutter, cartography, merchant and beacon payment, chosen by `InventoryType`; never result slots, never block containers. Move what fits into the inventory; the rest is recorded as overflow.
- `PaperInventories.clear`: empty those slots before `closeInventory()`, so the close hands nothing back.
- `Archiver.finish`: after clear and reset, check that the player's inventory and cursor are empty. If not, fail closed: remove the items, add them to the record's overflow with `ArchiveFiles.rewrite`, and audit `late-items`.
- Tests: unit tests for `loose` and `clear` per inventory type. Scenario `sec-workstation-inputs` covers an anvil with two shulker boxes, a stonecutter and a villager trade, each with a full inventory and a cursor item. It checks an empty player after the archive and both shulker boxes in the record.
- Question 285: step 1's "close any open menu" read as: workstation inputs are carried items.

### 2. M1: a hold that holds (`fix(guard): hold covers XP, wear and commands`)

- New intents for `PlayerPickupExperienceEvent`, `PlayerItemMendEvent`, `PlayerItemDamageEvent` and `PlayerCommandPreprocessEvent`, all denied for a held or arriving player. Commands get a message (`archive.hold-command`); the others are silent. XP orbs stay on the ground.
- When a hold starts, stop gliding and dismount, as block mode already does.
- Tests: `IntentRouterTest` for each new intent. Scenario `sec-hold-mending`: a bot with a damaged mending item in an orb pile is archived, and the archive succeeds.
- `docs/paper-api.md`: the four events.

### 3. H3: an empty inventory counts only after the clear (`fix(archive): journal the clear; vault waits for open archives`)

- New `JournalKind.ARCHIVE_CLEARING`, journalled after `VAULT_SEALED` and right before `clearAndReset`.
- `Archiver.recover`: an empty inventory finishes the archive only when the last step is `ARCHIVE_CLEARING`. Otherwise it's a mismatch: admins are alerted, and the player stays held (see 4).
- `Vault`: a season page whose archive operation is open for that player is shown read-only (`vault.pending`).
- Tests: `JournalRecoveryTest` and `ArchivingTest` for empty with and without the clearing step. `VaultTest` for refusal while open. Scenario `sec-empty-after-failure`: a crash point before the clearing step, an emptied inventory, then recovery refuses. A crash after the clearing step still finishes (`a07-crash-reset` and `a07-crash-archive-done` unchanged; `a07-crash-vault-sealed` must still finish when the inventory matches).
- Question 286: "already cleared" read as "cleared by step 4", which the new step records.

### 4. H2: contain and retry (`fix(archive): never leave an unarchived player free`)

- `Archiver`: when a live `finish` fails, abort cleanly before releasing anything. Remove the vault page this attempt sealed, move the archive file aside as `aborted-<time>`, journal `ARCHIVE_ABORTED` (a new finish kind for the operation), and keep the player held as pending.
- A pending player retries with a fresh capture every few seconds, up to a limit. After that, admins get `archive.stuck` and the player stays held until `/season archive <player>` succeeds.
- `Busy` at season end and at login waits for the hold to free (bounded), then archives. If it gives up, it's treated as Failed: alerted, audited, pending.
- `LoginService`: a failed or busy catch-up keeps the player held, never free in the world.
- `LaunchSequence.refusal`: also refuse while any player is pending, online or not, naming them.
- `/season reprocess <player> --restart`: for an operation whose recovery mismatches, abort it as above and archive the live inventory afresh, with an audit entry. This is new admin surface, so it gets question 287, and I'll drop it if you'd rather not have it.
- Tests: `ArchivingTest` (fail then abort then retry, busy then wait, vault page removed on abort). `LaunchSequenceTest` (pending refuses launch). Scenarios `sec-failed-archive-contained` (mending trigger with the M1 fix switched off by a scenario hook, so the abort path runs) and `sec-busy-at-end`.

### 5. M2: a bounded vault (`fix(vault): one withdrawal at a time, room checked first`)

- `Vault.clicked`: at most one transaction in flight and one waiting per player. Further clicks get a throttled `vault.busy`.
- New port method `Inventories.fits(player, item)`, checked on the server thread before journalling. A full inventory is refused (`vault.full`) without a transaction. The give still checks again and rolls back.
- Refused withdrawals go through windowed audit, as `HallAudit` does.
- `JournalRecovery`: keep an index of open operations by player, updated on append, so `open(player)` doesn't copy and scan the journal on the server thread. Also compact the journal at Homecoming, keeping open operations.
- Tests: `VaultTest` (flood of 1,000 clicks: at most two transactions, no journal lines for full-inventory clicks), `JournalRecoveryTest` (index agrees with a full scan). Scenario `sec-vault-flood`.
- Question 288: the spec's "clicks queued per player" read as a bounded queue.

### 6. M3: a tripwire that looks inside (`fix(tripwire): nested copies and placement`)

- `PaperMenus.copy` and `PaperDisplays.copy`: tag nested items too, through `BlockStateMeta` (shulker boxes) and `BundleMeta`.
- `PaperInventories.isArchived`: true if the stack or anything nested in it is tagged. `purge` removes such stacks whole.
- `PaperEvents.onPlace`: an archived-tagged item is never placed; it's deleted and reported.
- `InventoryOpenEvent` on any container: purge the top inventory of tagged copies (cheap, and it covers stashes outside the Hall).
- Tests: `PaperInventories` nested detection. Scenario `sec-tripwire-nested` (the testkit hands a bot a tagged shulker box copy inside a bundle and as a block; both are deleted and reported).
- `docs/paper-api.md`: `BlockStateMeta`, `BundleMeta`.

### Order and checks

The order is 1, 2, 3, 4, 5, 6: H1 stands alone, M1 removes H2's easy trigger, and 3 lays the journal ground that 4 builds on. After each commit, run the unit tests and that fix's `sec-*` scenario. Before pushing, run the full suite once in the background, and watch these most closely: `a04-*`, `a05-catch-up`, the five `a07-crash-*`, `a08-archive-view`, `a36-vault`, `m3-vault-crash-*` and `m4-curate-crash-*`.

Low items (L1 to L7) and I1 are left for a follow-up unless you want them in this branch. L1 and L4 are small: match the expected destination instead of a flag, and re-check at `MONITOR`.
