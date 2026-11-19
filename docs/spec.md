# Seasons — specification

Nov 17, 2026 · @Peter Woodhead

## Summary

**Seasons** is a server-side Paper plugin that runs a season format on one persistent Minecraft world, and players install nothing. When the group votes to end a season, an admin sets a date at least a week out and ends it by hand at a live event. Seasons then archives everything each player carries into their own Hall of Fame cell: a mannequin wearing their gear in that season's skin, a view-only chest of the rest, and a season card with their stats, advancement count, firsts and awards. It resets XP, advancements and beds, seals ender chests into a vault, and at launch moves everyone, with their registered pets, to a fresh spawn 16,000 to 23,000 blocks out in land nobody has explored.

Nothing in the old world is wiped. Until the group votes for Homecoming, the old world is locked: nobody can enter old seasons' land, change it or take anything from it, and ender chests stay sealed. Homecoming unlocks the whole world at once, so old bases, chests and items are all playable again and megaprojects carry on. Everywhere else there is no distance limit on exploring. Along the way Seasons gives each season's dragon slayer a new egg with a fanfare, keeps a museum, announces season firsts, posts to Discord, publishes markers to a public BlueMap, and lets players visit the Hall from any bed and come back safely. Settings are YAML, editable by hand or in-game. Every item move is journalled, so crashes and game updates lose nothing.

**Products**

- **Seasons**: the plugin. Paper first.
- **Seasons Web**: an optional add-on plugin serving a read-only live dashboard for phones, iPads and second screens: a public season clock, who's online, event feed, Hall of Fame and embedded BlueMap, plus leaderboards and full profiles for logged-in players.
- **Seasons API**: the public API (`seasons-api`) that Seasons Web and other plugins build on.
- **Seasons Adapter**: the layer that connects the shared core to one server platform. `seasons-adapter-paper` ships now. Adapters for Fabric and NeoForge can follow on the same core (see Multi-platform design).

## Overview

**Seasons** is a server-side Paper plugin that runs a season format. The world persists, every player's gear is archived into a Hall of Fame at season end, everyone restarts at a fresh spawn, and the old world stays locked, ender chests included, until a mid-season Homecoming unlocks everything. This spec covers the plugin in general and TheRanch's settings for Season 3; TheRanch's choices are named as such.

**Goals**

- Remove the manual rollover work: moving gear into chests, setting up armour stands, placing player heads.
- Give every season a level playing field and a fresh-world feel, including new terrain from game updates.
- Never destroy anyone's builds: nothing in the old world is wiped.
- Keep megaprojects in the same world, reachable later in each season.
- Work on any server and with any Hall of Fame design, not only TheRanch's.
- Stay maintainable across game versions, secure against theft and dupes, and readable by humans.

**Design principles**

1. Server-side only. Vanilla clients join with nothing extra installed.
2. The plugin's data store is the source of truth. The world only shows displays of it.
3. Deny by default inside protected areas.
4. Every step that moves items is journalled, atomic and safe to rerun.
5. Hard-enforce only what is tempting, easy to do quietly and spoils the season for everyone. Announce the rest.
6. Spatial setup happens in-game by standing in the spot. Everything else lives in readable files.

**Out of scope**

- Client-side features or a client mod.
- General land claims or protection outside the Hall and season zones.
- Bedrock players through Geyser. TheRanch has none; it may come later.
- Trimming or regenerating chunks. TheRanch only regenerates untouched End outer islands, with MCA Selector (see the runbook).
- Economy, ranks or chat formatting.

**Platform**

| Item | Target |
| --- | --- |
| Server | Paper (TheRanch runs Paper). Forks that run Paper plugins, such as Purpur and Pufferfish, work too |
| Game | Minecraft 26.3 |
| Java | 25 |
| Support policy | v1: Minecraft 26.3 only. From the next drop: the latest drop and the one before it |
| Later | Seasons Adapters for Fabric and NeoForge, Folia support, and Bedrock players through Geyser (none in v1) |

**Names.** Seasons is the plugin. Seasons Web is an optional add-on plugin that needs Seasons and reads only through Seasons API. A Seasons Adapter connects the core to one platform; the Paper adapter ships inside the Seasons jar.

**Licence.** Seasons, Seasons Web and seasons-api are source-available under the [PolyForm Noncommercial License 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0). Anyone may use, change and share them for noncommercial purposes, such as a friends' server. Selling them, or any commercial use, needs written permission from Peter Woodhead, who keeps the copyright and can grant commercial licences. Every source file starts with `// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0`, and `LICENSE` starts with `Required Notice: Copyright 2026 Peter Woodhead`. Contributions are accepted only if the contributor agrees, in CONTRIBUTING, that Peter may license them on any terms. Bundled libraries keep their own licences, listed in `THIRD_PARTY_NOTICES.md` (Configurate: Apache-2.0; bStats: MIT).

## Platform decision

Seasons is a **Paper plugin**. Earlier drafts targeted Fabric. This section records why that changed and how the choice rates on compatibility, upkeep and reach.

**Why Paper**

- TheRanch already runs on the Bukkit plugin family: DiscordSRV only runs there. Moving the server to Fabric for one mod would mean replacing DiscordSRV and re-checking every other plugin.
- Players still install nothing. Paper plugins are server-side only, just like the Fabric server mod would have been.
- Paper's API already covers everything that needed mixins on Fabric: mannequin figures, item storage that upgrades with the game, the dragon fight's "previously killed" flag, per-player world borders, Brigadier commands, and persistent data on items and entities. The plugin never touches server internals.
- It can reach the most servers (see below).

**Judgement**

| Question | Verdict | Why |
| --- | --- | --- |
| Backwards compatibility (older game versions) | Limited on purpose: Minecraft 26.3 only in v1, then the latest drop and the one before | 26.1 changed the world format and requires Java 25, mannequins only exist from 1.21.9, and Paper 26.2 moved to Adventure 5. Supporting 1.21.x would need a second build with armour-stand figures and older APIs, for servers that are unlikely to run a new season plugin. |
| Backwards compatibility (data) | Good, forwards only | Seasons' own files carry schema versions and migrate forward. Archived items record their DataVersion and are upgraded when a newer server loads them. Nothing downgrades: Paper refuses newer world and item data, so a rollback means restoring a backup. |
| Forwards compatibility and upkeep | Good | With no server internals, most game drops need only a version bump and a test run. Paper removes deprecated API in announced batches, and compiling against the newest API with deprecation warnings as errors flags each removal before it lands. Since its hard fork from Spigot, Paper starts updating at the pre-releases, so builds usually arrive within days of a drop. The newest API Seasons relies on is mannequins; it sits behind the figure port with an armour-stand fallback. |
| Reach | Most of the plugin-server world | In a bStats snapshot of about 147,000 Bukkit-family servers, 62% ran Paper, and Purpur (10%) and Pufferfish (1%) run Paper plugins too: about 73% in total. Spigot (17%) is not supported, because Seasons uses Paper-only API. Folia is not supported in v1. Fabric servers are not in bStats at all. |

**What reach means in practice.** Roughly 107,000 servers in that sample run software that can load Seasons. Two filters shrink that. Only servers on the newest drop qualify (26.3 at first), which starts as a minority after each big version change and grows with every drop; the same snapshot shows a long tail on 1.8.8, 1.12.2 and 1.16.5. And the season format suits a particular kind of server: survival SMPs, usually friend groups, that reset or prestige on a schedule. Installing is the easy part (drop a jar into `plugins/`); setup asks for an admin who can edit YAML and mark out a Hall, which the wand and templates turn into a building job rather than a coding one. A fair guess is a niche of hundreds of active servers if it is well documented and listed on Hangar and Modrinth, not tens of thousands. bStats will show the real number.

**Compared with the Fabric plan.** Fabric gets builds faster after a drop and doesn't depend on Paper's schedule, but the mod needed mixins into game code, which break more often per drop, and TheRanch would have had to leave DiscordSRV behind. The core stays plain Java either way, so a Fabric adapter remains possible later.

## Core concepts

Every rule in this spec follows from four scopes. What you carry becomes a trophy, the old world waits for Homecoming, one-offs live in the museum, and everything else is open frontier from day one.

| Scope | Contents | Access during a season | Mechanism |
| --- | --- | --- | --- |
| Season gear | Everything on a player at rollover, plus their advancements | View-only in their cell, forever | Archive |
| Old world | Bases, farms, megaprojects and their storage; ender chest contents | Locked until Homecoming: no entering, building or taking. After it, everything is unlocked and playable | Old-world zones and the ender vault |
| Server collectibles | The original dragon egg, each season's egg plinth, awards, one-off items | Display-only, forever | Museum |
| Frontier | Everything outside the old-world zones, including the new spawn | Open from launch, with no distance limit | None needed |

**Glossary**

| Term | Meaning |
| --- | --- |
| Season | A numbered play period, such as Season 3. One is active at a time. |
| Phase | Where the server is in the lifecycle: Active, Ending or Intermission. Launch is the instant switch back to Active. |
| Stage | Within Active: Frontier (old world locked, ender chests sealed), then Open (after Homecoming). |
| Planned end | The time reminders count down to. The season ends when an admin runs `/season end`. |
| Homecoming | The moment the old world opens for everyone: vaults unseal and every zone switches to its after-Homecoming mode. At TheRanch, when the group votes for it. |
| Rollover | Season end, then Intermission, then Launch. |
| Archive | The stored record of a player's gear, advancements and stats for one season. It can never be withdrawn. |
| Display | What the world shows of an archive: figure, trophies, plaque, archive view. Rebuilt from the archive at any time. |
| Hall | The protected Hall of Fame area: every gallery plus shared spaces. |
| Gallery | The part of the Hall for one season: a hallway, a room or a row of plinths. |
| Cell | One player's slot in a gallery. |
| Anchor | A typed point in a cell: figure, archive, plaque, trophy, entry or gate. |
| Zone | A named area with a mode (off, announce, block or no-build), an unlock condition and a mode for after it unlocks. |
| Old-world zone | A zone drawn around old land: one base, one farm, or the whole old world. It can be left open, announced, no-build or blocked until Homecoming. |
| Registered pet | A tamed animal its owner has registered to travel with them to the next season's spawn. |
| Ender vault | A sealed copy of a player's ender chest from rollover. Withdraw-only after Homecoming, for everything. |
| Museum | A gallery with no owners, for one-off trophies and each season's dragon egg plinth. |
| Journal | An append-only log written before any item moves, used for crash recovery. |

## Season lifecycle

A season runs Frontier, Open, Ending, Intermission, then Launch starts the next one. Anyone offline at season end catches up on their next login, so nobody is skipped or archived twice.

&#91;embedded content: season lifecycle · 5 phases, plus login catch-up\]

The top row is server-wide and moves only on an admin's command. The bottom row runs per player.

**Phases**

| Phase | What happens | Ends when |
| --- | --- | --- |
| Active: Frontier stage | Play from the new spawn anywhere outside the old world, which is locked; ender chests are sealed | An admin runs `/season unlock homecoming` after the group votes |
| Active: Open stage | The whole world unlocks: old bases, chests and items are playable again; the decorating window opens 7 days before the planned end | An admin runs `/season end` at the live event |
| Ending | Boss-bar countdown (default 10 minutes; `--now` skips it), then every online player is archived and sent to their cell. Owners get decorating.afterArchive (TheRanch: 15 minutes) to arrange their cell, then every gallery locks | The last online player is archived |
| Intermission | Everyone is in the Hall, awards and season firsts are announced, late joiners are archived on login | An admin runs `/season launch` |
| Launch (instant) | World spawn set; title, sound and fireworks; online players teleported to the new spawn in survival with their registered pets; new deaths objective; dragon flag reset; stat baselines taken; old-world zones locked | Becomes Active: Frontier at once |

**Ending is manual.** At TheRanch the group votes to end a season (a Discord poll; Seasons doesn't run votes), and the admin sets a planned end at least a week later with `/season schedule end <time>`. Reminders count down to that time in chat and on Discord at 7 days, 1 day, 1 hour and 10 minutes. Nothing happens on its own at the planned time: the admin runs `/season end` at the live event, so nobody is cut off mid-adventure. A server that wants a hard stop sets `schedule.autoEnd: true`; the countdown then starts at the planned time, or 5 minutes after the next start if the server was down.

**Launch is manual too** (`launch.requireManual: true`). It can be the command, or an op-only button block registered as a `launch` anchor in the Hall. The break between end and launch lasts as long as the admin wants: minutes at the same event, or days.

**Decorating window.** Opens `decorating.opensBefore` (default 7 days) before the planned end, or with `/season decorate open`. Cells are assigned when it opens (see Hall of Fame). After archiving, owners get decorating.afterArchive more (default 15 minutes; 0 turns it off) to arrange their cell around their new figure, in survival inside their own cell. Cells lock when it runs out, at /season decorate close, or at launch, whichever comes first.

**Per-player record.** UUID, last known name, seasons played with play time, archived-through season, cell per season, saved return position, vault state, stat baselines, registered pets, furthest block placed this season, firsts earned, last valid position for zones. The full schema is in Data model.

**On login**

| Situation | Action |
| --- | --- |
| Played a finished season, not yet archived, during Intermission | Archive, then teleport to their cell |
| The same, after Launch | Archive, then teleport straight to the new spawn with the season title, bringing their registered pets |
| Missed whole seasons | Archive into the last season they played. If no cell is free, archive without displays and alert admins. |
| New player | No archive, spawn at the season spawn, record created |
| Logged out inside the Hall | Stay there; `/hof leave` still returns them |

**Teleport safety.** Before every Seasons teleport: dismount, stop elytra flight, reset fall distance and fire, close menus, load the destination chunk, cross dimensions if needed. On arrival the player gets 5 s of damage immunity (configurable).

## Archiving a player

Archiving moves everything a player carries into an archive record, saves and resets their advancements, resets them for the new season, and only then renders their displays. Each player is archived exactly once per season.

**When it runs**

- At season end, for every online player, right after the galleries lock.
- On first login after season end, for everyone else (see Season lifecycle).
- On demand, with `/season archive <player>`.

**What is captured**

| Source | Goes to |
| --- | --- |
| Armour slots, main hand, off hand | Figure equipment and the archive record |
| Main inventory (36 slots) | Archive view |
| Cursor item and crafting grid | Returned to the inventory first, then archived |
| Ender chest | Ender vault (policy `vault`, `archive` or `keep`) |
| XP level and points | Season card, then reset |
| Advancements | Saved in the record with every awarded criterion; the count goes on the plaque; then revoked |
| Statistics | The season's difference, for the season card (vanilla stats themselves are kept) |
| Furthest block placed | Season card and the furthest-builder award |
| Position and dimension | Season card (where they ended) |
| Game profile with skin textures | Figure appearance, frozen at that season's skin |
| Registered pets | Not archived: they travel with the owner at launch (see Pets) |

With a mannequin figure, the helmet stays on the figure's head. With the armour stand fallback, the player head goes on the stand and the helmet goes to a trophy anchor, or into the archive view if the cell has none. Mob buckets, such as an axolotl in a bucket, are items like any other and are archived; a curator can return one on request.

**Steps** (journal entries in brackets)

1. Close any open menu and return cursor and crafting-grid items to the inventory.
2. Serialise each item with Paper's `serializeAsBytes`, which records the game's `DataVersion`, and the advancement snapshot into the archive record. Write it atomically and fsync. \[`ARCHIVE_STARTED` with a content hash\]
3. Write the ender vault record the same way. \[`VAULT_SEALED`\]
4. Clear inventory, ender chest, XP and effects. Revoke every advancement criterion except recipe unlocks (when recipes are kept). Extinguish fire, set health and hunger full, clear the respawn point.
5. Mark the player archived for that season. \[`ARCHIVE_DONE`\]
6. Render displays from the record. This step is idempotent and retried until it succeeds.
7. Teleport, following the lifecycle rules.

**Crash recovery.** On startup the plugin replays the journal. An `ARCHIVE_STARTED` with no `ARCHIVE_DONE` resumes at step 3 if the live inventory still matches the hash. If the inventory is already cleared, the archive is marked done. The key (player, season) makes every rerun harmless, and revoking advancements twice changes nothing.

**Resets** (configurable)

| Setting | Default | Options |
| --- | --- | --- |
| `xp` | reset | reset, keep |
| `enderChest` | vault | vault, archive, keep |
| `respawnPoint` | clear | clear, keep |
| `effects` | clear | clear, keep |
| `advancements` | archive-and-reset | archive-and-reset, keep |
| `recipes` | keep | keep, reset |
| `statistics` | keep (the card shows the season's difference) | keep |

**Advancement count.** The plaque and season card show completed advancements out of those visible in the advancement screen, such as "88 / 122". Recipe unlocks and hidden entries don't count. Because everyone starts each season at zero, vanilla's own advancement messages become a race, and Seasons records the first player to each one (see Stats).

**Undo.** `/season unarchive <player> <season>` restores the snapshot to the player, advancements included, removes their displays and writes an audit entry. It requires an empty inventory unless `--force` is given, and exists for mistakes only.

## Pets

Players can bring tamed animals into the new season. Everything else alive stays in the old world.

- **Who can travel.** Tamed animals the player owns: wolves, cats, parrots, horses, donkeys, mules, llamas and any other tameable mob. Ownership comes from the game, so wild mobs, villagers and wardens can never be registered.
- **Registering.** `/pet add` while looking at your pet within 5 blocks; `/pet list`; `/pet remove <name>`. Up to `pets.maxPerPlayer` (default 5). Unnamed pets get a name from their type, such as "Wolf 2". Registration closes when the season ends.
- **No smuggling.** A donkey, mule or llama with a chest can't be registered. A registered one that has a chest when it is time to travel stays behind, and the owner is told to remove the chest. Saddles and horse armour travel; nothing else carried does.
- **Travel.** When the owner reaches the new season's spawn (at launch, or at their first login after it), each registered pet is moved next to them. Seasons keeps each pet's last known position, updated whenever its chunk unloads. It loads that chunk without stalling the tick, finds the pet by its id, unleashes and dismounts it, then teleports it.
- A pet that dies is reported to its owner straight away (from 26.3, Paper says why an entity was removed), and its registration is dropped. One that can't be found at travel time is reported the same way.
- Registrations clear after travel, so each season starts with a fresh list. Pets are never archived.

## Hall of Fame

The Hall is made of galleries, galleries hold cells, and cells hold typed anchors. The plugin draws displays at those anchors from archive records, so it works with any build style.

&#91;embedded content: Hall structure · galleries, cells, anchors\]

TheRanch's Season 1 hallway already exists, built by hand; `/hof import` converts it (see Importing Season 1's cells). Season 2's hallway is built new, and the first rollover fills it.

### Galleries

- Fields: id, kind (`season` or `museum`), season number, dimension, bounds, entry point, exit anchors, assignment strategy, access mode, decorating window, renderer choice.
- A gallery can be any shape: a hallway, a room, a building, a row of plinths. Galleries may not overlap.

### Anchors

| Anchor | Required | Count | What Seasons does there |
| --- | --- | --- | --- |
| figure | Yes | 1 | Spawns the figure wearing the archived gear |
| archive | No | Any | The existing block opens the read-only archive view. Imported cells keep one per original chest, each opening at that chest's page |
| plaque | No | 0 or 1 | Places the season card (a written book on a lectern, or a text display) and the advancement count |
| trophy | No | Any | Item displays for awards, and the helmet when using armour stands |
| entry | No | 0 or 1 | Where visitors land with `/hof <player>` |
| gate | No | 0 or 1 | A plane of blocks the plugin opens and shuts (access mode `gate`) |

A cell has an id, optional bounds, an owner per season, and its anchors. Stored positions are absolute. Template anchors are relative and rotate with the template.

### Defining cells

All three ways write the same gallery file.

1. **Tag an existing build.** Run `/hof cell new <gallery> <id>`, then use the wand: right-click a block and pick an anchor type, or sneak-click two corners to set bounds.
2. **Template and stamp.** Build one cell in creative. Place structure blocks in DATA mode with metadata `seasons:figure`, `seasons:archive`, `seasons:plaque`, `seasons:trophy`, `seasons:entry` or `seasons:gate` (two markers for the gate's corners). Save the cell with a structure block into `templates/`. `/hof stamp` pastes copies, registers each marker as an anchor and replaces the marker with air. Two default templates ship in the jar: `alcove` and `plinth`.
3. **Edit the file.** Change `galleries/<id>.yml` by hand, then run `/season reload`.

### Assignment

- **`random` (default).** When the decorating window opens, everyone who has played the season gets a random free cell and a message saying which. Anyone who first plays later gets one at their next login, or at archive time.
- **Swaps.** During the decorating window, two players can swap cells: both run `/hof swap <other player>` within 60 seconds. Ops can reassign with `/hof cell assign`.
- Other strategies: `claim` (players run `/hof claim` during the window), `ordered` (by name or first join) and `manual` (ops assign).
- Any play time at all earns a cell (`minPlaytime`, default 0).
- With no free cell left, the player is archived without displays and admins are alerted. `/season end --dry-run` reports any shortfall in advance.

### Access and decorating

| Access mode | Behaviour |
| --- | --- |
| `open` (default) | Anyone walks in, in adventure mode. Visitors can look at everything and open view-only containers, and change nothing |
| `bounds` | Non-owners cannot enter a cell; owners only during the decorating window |
| `gate` | The gate plane is set to a configured block when locked, and to air when open |

- During the decorating window, owners are in survival inside their own cell and decorate with their own blocks.
- **Containers are allowed as decoration:** chests, barrels, shulker boxes and decorated pots. During the window only the owner can open them; anyone else sees a view-only copy. Hoppers, droppers, dispensers and crafters can't be placed anywhere in the Hall, and nothing can pull items out of a Hall container.
- **Cells lock when the season ends,** at the end of the countdown and before anyone is archived. Owners still in their cell switch to adventure mode. Whatever was placed becomes protected Hall, and every container opens as a view-only copy from then on, the same as the archive anchor. Items put in a cell container are on display forever, so a stash gains nothing.
- Owners can't keep decorating after the lock (`decorating.lockAtEnd: true`). The lock comes `decorating.afterArchive` after their archive (TheRanch: 15 minutes), or at launch if that is sooner.
- Cushions (new in 26.3) are allowed as decoration. Anyone can sit on one; only the owner can place or break them, and only during the window.

### Displays

- **Figure, default mannequin:** a profile with the skin textures captured at archive time, named "Peter · Season 2", standing pose (configurable per cell), immovable, invulnerable, no gravity, silent.
- **Figure fallback, armour stand:** arms shown, all equipment slots disabled, invulnerable, wearing the player head. Used on game versions without mannequins, or by config.
- Both renderers sit behind one `FigureRenderer` interface, so switching is a config change.
- Every display entity carries a `seasons:display` tag plus gallery, cell and season ids. Every interaction with one is cancelled.

### Archive view

- Opened from the archive anchor or the figure. Title: "\<name> · Season \<n>".
- A 54-slot chest view, with more pages when needed.
- Built from a throwaway copy. Every item is tagged `seasons:archived`, and all clicks are refused (see Security and integrity).

**Archive anchor block.** Use a real chest, barrel or any block the builder likes as the archive anchor. It opens like a chest, and hovering shows full tooltips: names, enchantments, lore, trims, durability, shulker box contents. No real container sits behind it, so nothing can be taken, hoppered out, broken open or exploded. Figures and trophy displays cannot show hover tooltips in the world, which is why the figure opens the same view.

### Importing Season 1's cells

Season 1's cells were built by hand. Each has an armour stand wearing the player's gear and head, and one or more chests of the rest. `/hof import` turns each cell into the same archive record a rollover makes, without losing or reordering an item, and the hallway looks the same afterwards. It runs once, on a copy of the world first.

1. **Tag the gallery and cells.** `/hof gallery create season-1 1`, then `/hof cell new season-1 <id>` and wand-select each cell's bounds. No anchors are needed: the import finds them.
2. **Dry run.** `/hof import season-1 --dry-run` scans every cell and writes `import-season-1.txt`: for each cell, the owner it found, the stand's gear, each container and its item count, item frames, and anything it can't place. Nothing changes.
3. **Owners.** The owner comes from the player head on the stand (its profile id). A cell with no head, two heads or someone else's head is flagged; set it with `/hof cell assign <cell> <player>` and run the dry run again.
4. **Import.** `/hof import season-1` converts cell by cell, journalled like an archive, so a crash resumes where it stopped.
5. **Check.** For every cell the import compares item counts and a hash of every item before and after, writes both to the audit log, and stops at the first mismatch. Then `/hof reconcile season-1` and walk the hallway.
6. **Undo.** `/hof import undo season-1 [cell]` puts every item back exactly where it came from, from the record, which keeps each item's original position.

What each part of a cell becomes:

| In the Season 1 cell | After the import |
| --- | --- |
| Armour stand with armour, held items and the player's head | A mannequin wearing the same gear, with the skin from the head's texture, so the figure looks as the player did then. The stand is removed |
| Each chest, barrel or shulker box in the bounds (a double chest counts as one) | One page of the archive view, in the same slot order. The container stays as an empty, protected archive anchor that opens the view at its own page |
| Shulker boxes inside chests | Kept whole, contents and all, and shown in the tooltip |
| Items in item frames | Trophy anchors showing the item; the item is stored in the record |
| Signs, books and other decoration | Left in place. Sign text is copied onto the season card |
| Statistics and advancements | Unknown for Season 1, so the season card says "Season 1 · imported" and leaves them off |

Items keep their own data: the server already upgraded them when the world was upgraded, and the record stores them with the current DataVersion. Cells with chests but no stand import without a figure. Remove the old barrier blocks after the import, because the Hall's own protection takes over.

## Visiting the Hall of Fame

Players visit from a safe spot next to a bed and return to where they left, or the nearest safe place if it has changed. That makes the Hall useless as an escape button, a shortcut or a stash.

**Entry**

- `/hof` (alias `/halloffame`) or sneak and right-click a bed with an empty hand.
- `/hof <season>` or `/hof <player>` lands at that gallery's or cell's entry anchor. Plain `/hof` lands at the Hall entry.

**Entry conditions** (each configurable)

| Check | Default |
| --- | --- |
| Distance to a bed | Within 3 blocks of any bed of any kind, straw beds included (option: own respawn bed only) |
| Hostile mobs nearby | None, using vanilla's can't-sleep check |
| Recent damage | None in the last 10 s |
| Movement | On the ground; not riding, gliding, falling or swimming |
| Hazards | Not in lava, fire or powder snow |
| Cooldown | None |
| Command rate limit | 1 attempt per 5 s |

A failed check tells the player which one failed, such as "Monsters are nearby".

**Return**

- `/hof leave`, or step on an exit anchor (a pressure plate or other block marked `exit` in the gallery).
- The player returns to the saved position, dimension, yaw and pitch, and their previous game mode is restored.
- **Return safety.** The saved spot may have changed while they were away. Before returning, Seasons checks it: a solid block to stand on, no lava, fire, magma or powder snow at feet or head, room to stand, and no hostile mobs within 8 blocks. If a check fails, the player goes to their bed (respawn point) if that passes the same checks, then the nearest safe spot within 8 blocks, then the season spawn, and is told why.
- On arrival the player gets 5 s of damage immunity.
- The saved position survives logouts and restarts.

**Sealing the Hall**

- Leaving the Hall's bounds by any route (walking, flying, pearls, pushing, falling) returns the player to their saved position. During Intermission there is no saved position, so they return to the Hall entry.
- Inside the Hall: adventure mode, no item dropping or pickup, no ender pearls or chorus fruit, no riding entities in. Visitors may walk into any cell with access mode open, and sit on cushions there.
- Exception: during the decorating window, owners are in survival inside their own cell's bounds.
- Ops are not exempt unless they hold `seasons.hof.bypass`.

## Old world, zones and Homecoming

The old world has two states. Until Homecoming it is locked: nobody can enter it, change it or take anything from it, and ender chests stay sealed. At Homecoming the whole world unlocks at once, so old bases, chests and items are all playable again: take a stack of Season 1 blocks and build with it, or upgrade a Season 1 base. Nothing is wiped. Seasons enforces the lock with zones an admin draws. A server can make a zone announce-only or no-build instead of blocked; TheRanch blocks.

**Zone model**

| Field | Values |
| --- | --- |
| `id` | Slug, such as `peter-base` or `iron-farm` |
| `dimension` | Any dimension id, such as `minecraft:the_nether` |
| `shape` | `box`, `cylinder`, `outside` (of a circle or box), `y-above`, `y-below` |
| `mode` | The mode while locked: `off`, `announce`, `block` or `no-build` |
| `lockedUntil` | An unlock condition (below) |
| `afterUnlock` | The mode once unlocked: `off` (default) or `no-build` |
| `exempt` | Regions that are never enforced (the Hall by default) |
| `message` | Text shown on a block or announce |

**Old-world zones at TheRanch**

| Area | Until Homecoming | After Homecoming |
| --- | --- | --- |
| Seasons 1 and 2 land in the overworld: one `old-world` circle around 0,0, plus a box for any build outside it | Locked (`block`): no entering, building, breaking or taking | Unlocked (`off`): everything playable |
| The Nether below the roof: one `old-nether` circle at an eighth of the radius, up to y 127 | Locked (`block`) | Unlocked (`off`) |
| The nether roof (y 128 and up) | Not locked | Not locked |
| Ender chests | Sealed in the ender vault, read-only | Everything withdrawable |
| The End | No zones: the main island is shared, and untouched outer islands are regenerated | Same |

An admin draws `old-world` standing at 0,0 with `/season zone add old-world cylinder <radius>`, with the radius just past the furthest Season 1 or 2 build (BlueMap shows it, and players list far-off builds in questionnaire Q18). Any build outside the circle gets its own box with the wand and `/season zone add <id> box`. Then `old-nether` the same way in the Nether with `--max-y 127`, so the roof stays open. Every new zone defaults to mode `block` until Homecoming and `off` after it. The new spawn must sit outside every zone, which validation checks; leave at least 2,000 blocks between the circle's edge and the spawn. The Hall at 0,0 is exempt, so `/hof` still works, and leaving the Hall's bounds sends a visitor back where they came from. The End has no zones: its main island is shared every season, and only untouched outer islands are regenerated (see the runbook).

**No distance limit outside the lock.** Players can go as far as they like from the new spawn, in any direction, up to the edge of the old world. Seasons tracks the furthest block each player places, which feeds the season card, an award, and the choice of next season's spawn.

**Block mode**

- Check every online player every 10 ticks, a tenth of players per tick. Remember each player's last valid position.
- On entry: teleport back to the last valid position, show a title and action-bar message, reset velocity. Dismount from vehicles first, and stop elytra flight.
- Cancel portal travel, teleports, and ender pearl or chorus fruit landings whose destination is inside a blocked zone.
- New nether portals generated by players must not link into a blocked zone. If they would, the trip is cancelled with a message.
- Nothing inside can be changed or taken from outside the edge either. Breaking, placing, opening, buckets and interaction with blocks or entities inside the zone are refused, and pistons, hoppers, liquids, fire and explosions can't cross the edge.
- A player who logs in inside a blocked zone (for example after an admin draws one around them) is sent to the season spawn and told why.
- Holders of `seasons.zone.bypass` are exempt.

**No-build mode**

- Anyone can enter, open containers, use farms, doors, buttons and levers, and trade.
- Placing or breaking blocks, filling or emptying buckets, placing or breaking item frames, paintings, armour stands and cushions, and lighting TNT are refused with a message.
- Explosions and fire inside the zone break and burn nothing.
- Holders of `seasons.zone.bypass` can repair.

**Announce mode**

- On entry, broadcast once per player per zone per day, such as "Peter entered the industrial district (opens at Homecoming)".
- Optionally post the same line to Discord.

**Unlock conditions**

| Kind | Example |
| --- | --- |
| Manual (TheRanch: when the group votes) | `/season unlock homecoming` |
| Fixed date | `date: 2027-01-10T19:00+11:00` |
| Time after launch | `after: 21d` |
| Milestone | `dragonKilled`, `playersReachedEnd: 3` |
| Combination | `any: [...]`, `all: [...]` |

The default is `manual`. The manual command always works as an override, whatever the condition.

**Homecoming event.** For everyone at once: a title and sound for everyone online, a chat broadcast and a Discord post, fireworks at the spawn. Every old-world zone switches to its `afterUnlock` mode in the same tick. Ender vaults switch to withdraw-only for everything. BlueMap's locked-zone areas are replaced by no-build areas where that applies. With the default afterUnlock: off, the whole world is unlocked: old bases, chests and items are all playable, so players can build with a stack of Season 1 blocks or upgrade a Season 1 base. Hall of Fame archives stay view-only: Homecoming unlocks the world, never the Hall.

**Showing zones.** Locked zones are published as BlueMap area markers, which Map Link shows on players' Xaero maps. For servers that use an `outside` circle as a frontier, a per-player world border can draw it (visual only; enforcement stays with the plugin). The vanilla world border is never used, because `/hof` takes players to 0,0.

## Ender vault, museum and dragon egg

Ender chests count as old-world storage, so they are sealed at rollover and opened at Homecoming. One-off trophies go in a display-only museum, and every season's dragon egg goes to whoever slays the dragon.

**Ender vault**

- At archive time, ender chest contents go into a per-player vault record stamped with the season.
- Before Homecoming, `/vault` shows the contents read-only.
- After Homecoming, `/vault` is withdraw-only. Clicking an item runs a server transaction: remove it from the record, persist, then give it to the player. If the give fails (full inventory), the removal is rolled back. The menu itself never moves items, and depositing is impossible.
- After Homecoming everything in the vault can be withdrawn, collectibles included.
- A player with vaults from several seasons sees one page per season, plus a "Returned" page for curator returns and dragon eggs that couldn't be handed over. The Returned page is withdrawable at once, even before Homecoming.
- Policy `archive` sends ender chest contents to the archive view instead (never returned). Policy `keep` leaves them in place.

**Museum**

- A gallery of kind `museum`: trophy anchors, no owners, display-only forever.
- `/museum add <anchor> [label]` takes the op's held item into the store and renders it as an item display with a plaque line (label, season, credit).
- `/museum remove <anchor>` returns the item to the op, with an audit entry.
- **Dragon egg wall.** One trophy anchor per season, tagged `egg`. At season end Seasons shows a display egg there with the plaque "Season 3 · slain by Taran". The real egg stays with its winner, in their archive if they carried it.
- First use at TheRanch: move the original dragon egg from Harryz101's ender chest onto the first egg plinth before the rollover. Otherwise it gets sealed in his vault.

**A dragon egg every season** (`dragon.newEggEachSeason`, default true)

- At launch, the plugin clears the End's "previously killed" flag through Paper's DragonBattle API.
- Players respawn the dragon with four end crystals, as in vanilla. Seasons tracks everyone who damages it.
- **The slayer gets the egg.** The slayer is the player who caused the final blow, including by arrow or by a crystal they set off. If no player caused it, the player who dealt the most damage is the slayer; if nobody damaged it, the egg stays on the portal as in vanilla. When the egg appears on the exit portal, Seasons removes it and gives it to the slayer. If their inventory is full or they have left, it goes to the Returned page of their vault.
- **Fanfare.** A title and sound for everyone online, fireworks over the exit portal, a chat broadcast, a Discord post, and the season first "Dragon slayer".
- Later kills in the same season drop no egg, as in vanilla.

**Collectibles** (armour trims, pottery sherds, music discs) are items like any other: archived if carried at rollover, locked with the old world until Homecoming, playable after it. The new spawn sits within reach of a stronghold, so every season can still be finished.

## Curation

Curators can move items out of an archive, but only through curation actions that are saved, audited and announced. The archive view itself never becomes an editable chest. Rare items, such as a heavy core or a nether star, are archived like everything else; a curator moving one into the museum is the rare exception, not a routine.

**Curator view.** Open it with `/hof curate <player> <season>`, or by sneak-clicking a cell's archive anchor or figure while holding `seasons.curate.view`. It shows the figure's equipment and the archive contents, with full tooltips.

**Actions** (click an item, pick an action, give a reason)

| Action | Where the item goes | Permission |
| --- | --- | --- |
| Museum | Straight into a chosen museum trophy anchor with a plaque, never through anyone's inventory | `seasons.curate.museum` |
| Return to owner | A "Returned" page in the owner's ender vault, withdrawable at once (even before Homecoming), so it works while they are offline | `seasons.curate.return` |
| Take | The curator's inventory, with the `seasons:archived` tag removed | `seasons.curate.take` |
| Destroy | Removed from the record and kept 30 days in a recycle bin; `/hof curate undo <id>` restores it | `seasons.curate.destroy` |

**Transaction.** Each action removes the item from the record, saves the record, then delivers the item. If delivery fails (full inventory, missing anchor), the removal is rolled back. Clicks are queued per curator, as with the vault.

**Permissions.** `seasons.curate.view`, plus one node per action. Players never get them by default. They default to ops, who can already spawn any item, so this adds no new power. With LuckPerms, grant them to a `curator` group instead.

**Transparency.** Every action needs a reason (`curate.requireReason`, default true). It writes an audit entry: item snapshot, owner, season, slot, destination, curator and reason. It also posts to the admin webhook, and tells the owner in-game (at next login if offline) and on Discord.

**Effect on displays.** Removing equipped gear updates the record, and reconciliation redraws the figure from it.

## Stats, season cards and awards

The plugin snapshots vanilla statistics at the start of each season and compares them at archive time. The differences, plus advancements and the furthest block placed, become a season card in each cell, a set of awards and the season firsts.

**Baselines.** At launch, and at a player's first login in a season, their vanilla statistics are copied into their record. The archive stores end minus baseline.

**Furthest block placed.** On every block placed in the overworld, Seasons compares its horizontal distance from the season spawn, and from 0,0, with the player's best so far. The check is in memory and saved with the player record. Blocks placed in the Hall don't count. `/season status` shows the season's furthest block from 0,0, which guides where the next spawn goes.

**Season card** (a written book on the plaque anchor, or a text display)

- Name, season, first and last day played.
- Highlights: play time, deaths, mob kills, player kills; distance travelled (walking, sprinting, swimming, flying, elytra, boats, minecarts and horses combined); diamonds and ancient debris mined; blocks placed and mined; advancements, such as "88 / 122"; furthest block placed; dragon credit; where they ended the season.
- Every season first they earned.
- Further pages: every statistic on the General tab of the vanilla statistics screen, as this season's difference.
- Seasons Web's profile shows the full per-item, per-block and per-mob tables.

**Awards** (configurable list, computed at season end, announced during Intermission)

| Award | Statistic |
| --- | --- |
| Most deaths | Deaths |
| Furthest travelled | Combined distance |
| Most dedicated | Play time |
| Deepest pockets | Diamonds mined |
| Dragon slayer | The season's dragon slayer |
| Furthest builder | Furthest block placed from the season spawn |
| Most advancements | Advancements completed this season |

Each winner gets a trophy item display in their cell and a line on the museum's season plaque. Ties share the award.

**Season firsts** (broadcast in chat and on Discord, so there is friendly competition, and recorded on the season card)

- First to the Nether, first diamond, first to the End, first elytra, and the dragon slayer.
- First to each advancement. Advancements reset every season, so every one is a race again.
- First to have each item on a configurable rare-items list in their inventory, such as a heavy core, a totem of undying or a trident. Seasons notices within 30 seconds, in the inventory sweep it already runs.

**Deaths display**

- One vanilla scoreboard objective per season (`seasons_deaths_s3`, criterion `deathCount`), so history is kept.
- Shown in the tab list by default, so it never clutters the screen. Options: `list`, `sidebar`, `below_name`, `none`.
- A new objective is created at launch, which acts as the reset.

## Integrations

Every integration is optional and loads only when its plugin or setting is present. Seasons runs fully without any of them.

| Integration | What Seasons does | Notes |
| --- | --- | --- |
| [BlueMap](https://bluemap.bluecolored.de/) | Publishes a `Seasons` marker set: season spawn points, spawn candidates before launch, the Hall, the museum, and areas for locked zones (no-build areas after Homecoming) | Uses BlueMap's API; markers are kept in Seasons' data and republished when BlueMap reloads |
| Live player positions | Nothing | BlueMap's own settings: `hide-vanished: true` and `hide-sneaking: true`, so players disappear from the map while invisible or sneaking |
| BlueMapPlayerControl (optional plugin) | Nothing | Each player can hide themselves from the map with `/bmpc hide` |
| Shared waypoints | `/season marker add <set> <name>` saves the op's position as a BlueMap point, such as the hub or a megaproject | Server-wide waypoints; personal ones stay in Xaero |
| [Map Link](https://modrinth.com/project/maplink) | Nothing server-side | A client mod: it reads BlueMap's players, markers and areas into Xaero's maps, so everything above appears for anyone running it |
| Xaero's Minimap and World Map | Nothing | Client-side personal waypoints; players can start a "Season 3" set |
| Discord | Outbound webhooks only, no bot: reminders, season end, awards, season firsts, the dragon slayer, launch, Homecoming, announce-mode zone entries | A second admin webhook receives alerts (tripwire hits, reconcile repairs, recovery runs). URLs come from the secrets file or environment. |
| Chat bridges (DiscordSRV and similar) | Nothing | Game broadcasts already appear in their relay |
| Permissions | Bukkit permission checks, so LuckPerms or any permissions plugin works | Without one, nodes use the defaults in paper-plugin.yml: player nodes for everyone, admin and curation nodes for ops |

Spawn candidates are shown on BlueMap only as small previews, one bounded BlueMap map per candidate, so the group can vote on terrain without the whole new area being revealed. The chosen area stays unrendered until launch. Both are BlueMap settings, covered in the runbook.

## Web companion

Seasons Web, a separate add-on plugin, turns each server into a live dashboard for phones, iPads and second monitors. A static public site explains the plugins and sends people on to their own server's dashboard. It needs Seasons installed, reads game data only through Seasons' API, and is off by default and read-only in v1.

**Packaging and compatibility**

- Two plugin jars built from one repository, Seasons and Seasons Web, versioned, tested and released together.
- Seasons runs on its own. Removing Seasons Web leaves Seasons and its data untouched.
- Seasons Web declares Seasons as a required dependency in `paper-plugin.yml`, so Paper always enables Seasons first. On enable it fetches the Seasons API from Bukkit's services manager and checks its version against the range it supports, such as `>=1.2 <2.0`. With any other version it disables itself and says which version it needs.
- The boundary: Seasons publishes read-only snapshots and events through `seasons-api`. Seasons Web reads them and owns everything web: the HTTP server, `/web` commands, logins, sessions, `web.yml` and the dashboard page. It registers the `/web` commands itself through Paper's Commands API.
- `seasons-api` follows semantic versioning. A change that breaks Seasons Web needs a new major version.
- Its files live apart: `plugins/SeasonsWeb/web.yml`, and session records in `<world>/seasons-web/`, with tokens stored only as hashes.
- It registers its own permissions, `seasons.web.*`.
- CI runs the end-to-end tests with both plugins installed, on every supported game version.

**Two sites**

| Site | Hosted on | Holds |
| --- | --- | --- |
| Public site | GitHub Pages behind Cloudflare | What Seasons is, the docs, and a connect page. No data and no secrets. |
| Instance dashboard | The Minecraft server, behind the owner's Cloudflare Tunnel or any HTTPS reverse proxy | The dashboard page and its API, served by Seasons Web. BlueMap gets its own address through the same tunnel or proxy, or shares this one at `/map`. The owner decides whether the map is public, for example with Cloudflare Access; TheRanch's is public at map.petepeterepeat.com. |

**Connect flow**

1. On the public site, the player types the server's address.
2. The page fetches `https://<server>/.well-known/seasons.json`: server name, plugin version, API version, enabled features and login methods, all public. It refuses anything that isn't https or isn't a Seasons instance.
3. The browser goes to the instance's dashboard. Every later call is to that same site.

Why not call the instance from the public site: browsers block cookies set by another site, and Safari on iPad does so by default. Serving the dashboard from the instance keeps the session in an HttpOnly, Secure, SameSite=Strict cookie, and keeps tokens out of JavaScript.

**Login (device code)**

1. The dashboard shows a one-time code, such as `K7QX-2M`.
2. The player types `/web link K7QX-2M` in-game.
3. The dashboard, polling, receives a session for that Minecraft account.

- Codes: 8 characters from an alphabet with no look-alike letters, single-use, expiring after 5 minutes, at most 5 attempts per IP per minute.
- Sessions last 30 days (configurable). `/web sessions` lists them and `/web logout [all]` ends them.
- Discord login may come later. Microsoft sign-in is out of scope.

**Access levels**

| Level | Who | Sees by default |
| --- | --- | --- |
| Public | Anyone with the address, when `public: true` | Season clock and countdowns, rules, who's online and the area they're in, the event feed, the embedded map, the Hall of Fame and museum browser, shared waypoints, links |
| Player | Logged in with a linked account | Everything public, plus leaderboards, full profiles with every statistic, and archived gear as text |
| Admin | Players with `seasons.web.admin` | Server health (TPS, tick time, memory), the audit log, pending players |

Every widget and field has a visibility setting in `web.yml`. Players can hide themselves from leaderboards with `/web privacy`.

**Widgets**

| Kind | Widget | Data from |
| --- | --- | --- |
| Live | Season clock with Homecoming and end countdowns | Season state |
| Live | Online players: head, dimension, region (the biome, or the nearest named marker or zone within 200 blocks; never coordinates), AFK | Player snapshots |
| Live | Leaderboards: deaths, play time, distance this season | Stat deltas |
| Live | Event feed: season firsts, dragon kill, joins, announce-mode zone entries | Event queue |
| Live | Map: BlueMap embedded (a link if framing is blocked). Players appear on it under BlueMap's own rules, so anyone sneaking, invisible or hidden with /bmpc hide doesn't | BlueMap |
| Live, admin | Server health | Server metrics |
| Profile | Seasons played, season cards, awards, Hall of Fame cell, archived gear as text with names, enchants and trims | Player records and archives |
| Reference | Hall of Fame and museum browser, season history, current rules and zones, shared waypoints | Galleries, seasons, markers |
| Tool | Overworld and Nether coordinate converter | Runs in the browser |
| Links | Server playlist (Spotify's official embed), Discord, Trello, BlueMap, rules | `web.yml` |

**Second-screen mode.** A landscape layout for an iPad on a stand: large type, dark theme, auto-refresh, and the Screen Wake Lock API to stop the display sleeping. Its widgets and their order come from `web.yml`.

**Example: `plugins/SeasonsWeb/web.yml`** (TheRanch's values; placeholders in brackets)

```yaml
schema-version: 1
enabled: true
bind: 127.0.0.1:8124              # BlueMap keeps 8100
public-url: https://seasons.petepeterepeat.com
public: true
proxy:
  trusted: [127.0.0.1]            # cloudflared runs on the same machine
  client-ip-header: CF-Connecting-IP
links:
  - { label: Server playlist, url: "https://open.spotify.com/playlist/[ID]", embed: spotify }
  - { label: Discord, url: "https://discord.gg/[INVITE]" }
  - { label: Planning board, url: "https://trello.com/b/[BOARD]" }
  - { label: Map, url: "https://map.petepeterepeat.com", embed: frame }
screens:
  second-screen:
    layout: landscape
    widgets: [season-clock, online, leaderboard, event-feed, map, links]
visibility:
  online.region: public           # public, players, admins or hidden
  event-feed: public
  leaderboards: players
  profiles: players
  server-health: admins
sessions:
  lifetime: 30d
```

**API** (read-only in v1, JSON, versioned under `/api/v1`)

| Endpoint | Access | Returns |
| --- | --- | --- |
| `GET /.well-known/seasons.json` | Public | Instance metadata |
| `POST /api/v1/auth/device`, `GET /api/v1/auth/poll` | Public, rate-limited | A device code; the session cookie once linked |
| `GET /api/v1/season` | Public | Phase, stage, dates, countdowns |
| `GET /api/v1/players/online` | By visibility | Online players |
| `GET /api/v1/players/{uuid}` | Own profile, plus public fields of others | Profile, seasons, cards, awards |
| `GET /api/v1/hall/{season}`, `/hall/{season}/{uuid}` | By visibility | Gallery, and archive contents as text |
| `GET /api/v1/leaderboards?season=` | By visibility | Rankings |
| `GET /api/v1/events` | Player | A server-sent event stream |
| `GET /api/v1/waypoints`, `/api/v1/links` | By visibility | Shared waypoints and links |
| `GET /api/v1/admin/health` | Admin | Tick time, TPS, memory |

**Security**

- Off by default and read-only in v1. Admin actions stay in-game.
- Listens on localhost only. HTTPS comes from the tunnel or proxy. Binding beyond localhost without an https `public-url` stops the module from starting.
- Built on the JDK's own HTTP server with virtual threads, with no web framework.
- A strict content security policy: no third-party scripts, frames only from allow-listed sites (the BlueMap address and open.spotify.com), and the dashboard itself can't be framed.
- Player-written text and link labels are shown as plain text. Link URLs must be https.
- Never served: IP addresses, unpublished coordinates, secrets, or session tokens in response bodies.
- Rate limits per IP and per account. Logins, failed codes and revocations go to the audit log.
- Behind a tunnel or proxy every request arrives from 127.0.0.1, so per-IP rate limits read the visitor's address from the header named in proxy.client-ip-header (CF-Connecting-IP for Cloudflare). That header is believed only from the addresses in proxy.trusted; from anywhere else it is ignored.
- Responses that depend on a login carry Cache-Control: private, no-store, so Cloudflare never caches one person's data for another. Public responses may be cached for a few seconds.
- The event stream sends a keep-alive comment every 30 s, so a proxy's idle timeout (100 s on Cloudflare) never cuts it.
- No bundled Minecraft textures: items appear as names and enchant text with our own icons, and skins load from Mojang's texture servers.

**Performance.** Seasons Web never touches game state. It reads the latest snapshot through an atomic reference. The server thread refreshes online players' snapshots every 30 s (configurable), and a worker reads offline players' stats from disk. Live events reach clients through a bounded queue that drops the oldest events for a client that falls behind.

**Hosting at TheRanch.** Players type one address and open two web pages, with no port numbers anywhere. Minecraft's own port is the only one open to the internet; web traffic comes in through a Cloudflare Tunnel.

| Address | Serves | How |
| --- | --- | --- |
| play.petepeterepeat.com | Minecraft (Java) | An A record to the server's IP, DNS only (grey cloud), because Cloudflare's proxy can't carry Minecraft traffic, plus an SRV record so the port never has to be typed |
| map.petepeterepeat.com | BlueMap, public | Cloudflare Tunnel to BlueMap's webserver at 127.0.0.1:8100 |
| seasons.petepeterepeat.com | Seasons Web dashboard | Cloudflare Tunnel to Seasons Web at 127.0.0.1:8124 |

- **DNS** (203.0.113.10 stands in for the real IP): `play  A  203.0.113.10`, DNS only, and `_minecraft._tcp.play  SRV  0 5 25565 play.petepeterepeat.com`. If the host gives a port other than 25565, only the SRV port changes.
- **Tunnel.** cloudflared runs on the server as a service with two public hostnames: `map` to `http://localhost:8100` and `seasons` to `http://localhost:8124`. Cloudflare creates their DNS records and HTTPS certificates. The old `:25572` map address is retired and its port closed.
- **BlueMap.** `ip: "127.0.0.1"` and `port: 8100` in `webserver.conf`, so the map is reachable only through the tunnel. BlueMap 5.24 and newer send keep-alives on their live stream, so Cloudflare doesn't close it. To let the dashboard frame the map, BlueMap's `additional-headers` sets `Content-Security-Policy: frame-ancestors https://seasons.petepeterepeat.com`.
- **On a game panel** where cloudflared can't run, make `map` and `seasons` proxied (orange cloud) A records instead, and add a Cloudflare Origin Rule for each hostname that overrides the destination port to the panel's allocated port. Visitors still see no port.

## Commands and permissions

Players get a handful of commands, plus four web commands with the Seasons Web add-on. Everything else is admin-only. Without a permissions plugin, admin and curation nodes default to ops (Bukkit has no op levels). Bypass nodes default to nobody, ops included, and must be granted explicitly.

**Player commands** (granted to everyone by default)

| Command | Permission | Does |
| --- | --- | --- |
| `/hof [season\|player]` | `seasons.hof.visit` | Visit the Hall (entry conditions apply) |
| `/hof leave` | `seasons.hof.visit` | Return to the saved position, with return safety |
| `/hof swap <player>` | `seasons.hof.swap` | Swap cells during the decorating window; both players run it within 60 s |
| `/hof claim [cell]` | `seasons.hof.claim` | Claim a free cell, only when the gallery uses `claim` assignment |
| `/vault` | `seasons.vault` | View, or after Homecoming withdraw from, your ender vault |
| `/pet add` / `remove <name>` / `list` | `seasons.pet` | Register up to 5 tamed pets to travel to the next season's spawn |
| `/season info` | `seasons.info` | Phase, stage, the planned end, your cell, your archive status |

**Web commands** (only with Seasons Web installed and enabled; `seasons.web`, granted to everyone)

| Command | Does |
| --- | --- |
| `/web link <code>` | Link the dashboard on another device to your account |
| `/web sessions` | List your logged-in devices |
| `/web logout [all]` | End one session, or all of them |
| `/web privacy` | Show or hide yourself on leaderboards |

Admin dashboard widgets need `seasons.web.admin`, which is never granted by default.

**Season admin** (`seasons.admin.season`)

| Command | Does |
| --- | --- |
| `/season status` | Phase, planned end, counts of archived and pending players, pending pet travel, furthest block from 0,0, journal health, adapter capability gaps |
| `/season init <season>` | On a server that has never run Seasons, record the season in progress (TheRanch: 2), so the first /season end archives it. Refused once any season has been recorded |
| `/season schedule end <time>` | Set the planned end (ISO time with time zone); reminders count down to it |
| `/season end [--dry-run] [--now]` | Start the end countdown at the live event, or skip it; the dry run reports everything that would happen |
| `/season cancel` | Cancel a countdown before archiving starts |
| `/season decorate open\|close` | Open or close the decorating window early; opening assigns cells |
| `/season launch [--dry-run]` | Launch the next season |
| `/season spawn candidate add <name>` / `remove <name>` / `list` | Mark spawn candidates at the op's position; they appear on BlueMap |
| `/season spawn set [candidate]` | Set the next season's spawn to the op's position or a candidate |
| `/season unlock homecoming` | Homecoming, when the group votes |
| `/season unlock <zone>` / `lock <zone>` | Unlock or relock one zone |
| `/season archive <player>` | Archive one player now |
| `/season unarchive <player> <season> [--force]` | Restore a snapshot (mistakes only) |
| `/season reprocess <player>` | Rerun pending steps from the journal |
| `/season firsts` | List this season's firsts |

**Zones, config and markers** (`seasons.admin.config`)

| Command | Does |
| --- | --- |
| `/season zone add <id> <shape> [args]` | Create a zone; `box` with no arguments uses the wand selection |
| `/season zone mode <id> <off\|announce\|block\|no-build>` | Change a zone's mode while locked |
| `/season zone after <id> <off\|no-build>` | Set what a zone becomes after Homecoming |
| `/season zone remove <id>` / `list` | Remove or list zones |
| `/season config get <path>` / `set <path> <value>` | Read or change a setting, written back to the file |
| `/season reload` / `validate` | Reload files atomically, or only validate them |
| `/season marker add <set> <name>` / `remove` | Shared BlueMap waypoint at the op's position |
| `/season audit [player]` | Show recent audit entries |

**Hall admin** (`seasons.admin.hof`)

| Command | Does |
| --- | --- |
| `/hof gallery create <id> <season>` | Create a gallery; then bounds, entry, exit subcommands |
| `/hof cell new <gallery> <id>` | Create a cell; then anchor \<type>, bounds |
| `/hof cell assign <cell> <player>` / `unassign` | Manual assignment, overriding the random draw |
| `/hof wand` | Give the anchor and zone wand |
| `/hof stamp <template> <gallery> <count> [--step x y z] [--rotate 0\|90\|180\|270]` | Paste cell templates and register their anchors |
| `/hof import <gallery> [cell] [--dry-run]` | Convert hand-built cells into archive records; /hof import undo \<gallery> \[cell\] puts every item back |
| `/hof reconcile [gallery]` | Check and repair displays now |
| `/hof restore <player> <season>` | Re-render one player's displays |
| `/museum add <anchor> [label]` / `remove <anchor>` | Manage museum trophies and egg plinths |

**Curation** (`seasons.curate.*`: never granted to players; ops by default)

| Command | Does |
| --- | --- |
| `/hof curate <player> <season>` | Open the curator view for one archive |
| `/hof curate undo <id>` | Restore a destroyed item from the recycle bin |

**Bypass nodes** (never granted by default, ops included): `seasons.zone.bypass`, `seasons.hof.bypass`.

## Configuration and data files

Human-owned settings, command-editable definitions and plugin-owned state live in separate files. Hand edits and in-game edits never fight, and runtime state never ends up in a file people edit. World-specific files live in the world folder, so a world backup carries them.

**Layout**

```
plugins/Seasons/
  config.yml              # human-owned settings, fully commented
  messages.yml            # every player-facing string
  secrets.yml             # webhook URLs (or env SEASONS_DISCORD_WEBHOOK, SEASONS_ADMIN_WEBHOOK)
<world>/seasons/
  seasons/3.yml           # spawn, zones, unlocks, schedule (command-editable)
  galleries/season-3.yml  # gallery, cells, anchors (command-editable)
  museum.yml
  templates/*.nbt         # cell templates
  data/players/<uuid>.json
  data/archives/s3/<uuid>.dat   # serializeAsBytes, with DataVersion
  data/vaults/<uuid>.dat
  data/journal.log
  audit/2026-11.jsonl
  backups/                # last 5 copies of every edited file
```

**Format and library.** YAML through [Configurate](https://github.com/SpongePowered/Configurate), mapped onto Java records, with comments generated from annotations. Every file carries `schema-version`. Configurate's versioned transformations migrate old files on load, and the original is kept in `backups/`. Unknown keys log a warning, not an error.

**Example: `plugins/Seasons/config.yml`** (TheRanch's values)

```yaml
schema-version: 1
schedule:
  autoEnd: false                 # the season ends when an admin runs /season end
  reminders: [7d, 1d, 1h, 10m]
  endCountdown: 10m
launch:
  requireManual: true
resets:
  xp: reset
  enderChest: vault
  respawnPoint: clear
  effects: clear
  advancements: archive-and-reset
  recipes: keep
cells:
  assignment: random             # random, claim, ordered, manual
  minPlaytime: 0
  access: open                   # open, bounds, gate
  containers: [chest, barrel, shulker_box, decorated_pot]
decorating:
  opensBefore: 7d
  lockAtEnd: true
  afterArchive: 15m              # time to arrange your cell once archived; then it locks
pets:
  enabled: true
  maxPerPlayer: 5
firsts:
  rareItems: [heavy_core, totem_of_undying, trident, nether_star]
hof:
  entry: { bedDistance: 3, anyBed: true, noHostiles: true, noDamageFor: 10s }
  returnSafety: { hostileRadius: 8, fallbacks: [bed, nearby, spawn] }
  arrivalImmunity: 5s
dragon:
  newEggEachSeason: true
  eggTo: slayer                  # slayer or portal
deaths:
  display: list
```

**Example: `seasons/3.yml`**

```yaml
schema-version: 1
season: 3
spawn: { dimension: minecraft:overworld, pos: [18450, 72, -9120], yaw: 180 }
spawnCandidates:
  north-mesa:  { pos: [3200, 80, -20100] }
  east-jungle: { pos: [19400, 70, 4100] }
schedule:
  plannedEnd: null               # set with /season schedule end
homecoming:
  unlock: { manual: true }
zones:
  old-world:  { dimension: minecraft:overworld, shape: { cylinder: { center: [0, 0], radius: 12000 } }, mode: block, lockedUntil: homecoming, afterUnlock: off }
  old-nether: { dimension: minecraft:the_nether, shape: { cylinder: { center: [0, 0], radius: 1500, maxY: 127 } }, mode: block, lockedUntil: homecoming, afterUnlock: off }   # the roof stays open
```

All coordinates are placeholders. Item groups name Seasons' own groups or item ids; the adapter resolves them.

**Editing**

- In-game commands edit through the same model as the files. Writes are atomic (temp file, then rename) and keep the last 5 copies.
- Hand edits take effect with `/season reload`.
- Reload parses every file, validates, then swaps the whole config in one step. On any error the previous config stays live, and the error (file, line, message) goes to the console and online admins.
- Rewriting YAML can drop hand-written comments, so command-edited files hold data only. Documentation lives in generated comments and `config.yml`.

**Validation rules**

- Galleries do not overlap; cells sit inside their gallery; anchors sit inside their cell's bounds.
- Every cell has a figure anchor, and every dimension id exists.
- Zone shapes are well-formed, and the season spawn and every spawn candidate sit outside every zone.
- The season spawn is within 24,000 blocks of 0,0, so strongholds are in reach. Beyond that is a warning: Java's last stronghold ring ends at 24,320 blocks.
- Item groups name known items; `pets.maxPerPlayer` is between 0 and 10.
- Dates include a time zone; webhook URLs use https.
- On `/season end --dry-run`: the gallery has enough cells for everyone who played the season, and the museum has an egg plinth for the season.

**Plugin-owned data**

- Player records are JSON with a `format-version`.
- Archives and vaults store each item with Paper's `ItemStack#serializeAsBytes`, which records the game's `DataVersion`. On load Paper upgrades older items, so they survive game updates.
- The journal is append-only and fsynced before each world change. It is compacted after each completed rollover.

## Data model

All plugin-owned data carries a `format-version` and migrates forward on load. Items are stored as vanilla item data with their `DataVersion` (see Multi-platform design). Times are UTC ISO-8601. Positions are `{dimension, x, y, z, yaw, pitch}`.

| Record | File | Fields |
| --- | --- | --- |
| Season state | `data/season.json` | Season number, phase, stage, planned end, countdown state, launch time, Homecoming time, decorating window state, spawn, firsts recorded (key, player, time) |
| Player | `data/players/<uuid>.json` | UUID, last name, seasons played (play time, first and last seen), archived-through season, cell per season, saved return position, last valid position, stat baselines, registered pets (entity UUID, type, name, last known position), furthest block (from spawn and from 0,0, with its position), firsts earned, privacy flags, pending notices |
| Archive | `data/archives/s<n>/<uuid>.dat` | Format version, player, season, created time, content hash, equipment and inventory slots as vanilla item data, advancement snapshot (advancement id to awarded criteria with times), XP, statistics difference, furthest block, final position, skin profile (texture value and signature), awards, firsts |
| Vault | `data/vaults/<uuid>.dat` | Pages by season, each slot's item, the Returned page, withdrawal history |
| Gallery | `galleries/<id>.yml` | As in Hall of Fame, plus each cell's owner per season |
| Zone | `seasons/<n>.yml` | As in the zone model |
| Museum | `museum.yml` and `data/museum/<anchor>.dat` | Anchor, label, season, credit, stored item |
| Journal entry | `data/journal.log` | Sequence, time, kind (`ARCHIVE_STARTED`, `VAULT_SEALED`, `ARCHIVE_DONE`, `VAULT_WITHDRAW`, `CURATE_STARTED`, `CURATE_DONE`, `EGG_DELIVERED`, `PET_TRAVELLED`), player, season, payload hash |
| Audit entry | `audit/YYYY-MM.jsonl` | Time, actor, action, target, result, details |

**Writes.** Every write is temp file, fsync, rename, keeping the last 5 copies of human-editable files. One player's record writes go through the single writer thread in order, so they never interleave. A record is loaded once per session and cached; saves are coalesced so a busy player costs at most one write per 30 seconds, plus an immediate write at every journal step.

## Security and integrity

The main defence is that no archived item exists in the world. Everything else is layered on top, so one missed check is detected and repaired rather than lost.

**Threats and controls**

| Threat | How it would happen | Controls |
| --- | --- | --- |
| Stealing archived gear (anyone's, including your own) | Break, open, hopper, explode or interact with Hall blocks and entities | No real archived items in the world: displays plus a virtual archive view. Deny-by-default Hall rules. Reconciliation. Tripwire. |
| Duping through the archive view | Shift-click, number-key swap, off-hand swap, Q-drop, drag, double-click collect, clone | Every click type refused server-side, full resync after each, view built from a throwaway copy, one end-to-end test per click type |
| Losing or duping items in a crash mid-rollover | Server stops between steps | Journal, atomic writes with fsync, idempotency key (player, season), recovery on startup |
| Duping through vault withdrawal | Racing clicks, full inventory, disconnect mid-click | One server transaction per click: remove, persist, give, roll back on failure; clicks queued per player |
| Skipping the fresh start | Walking, elytra, pearls, chorus fruit, portals or boats into old bases and farms | Zone checks on movement, teleports, portal destinations and landings |
| Smuggling items into the new season | Pack animals with chests, mob buckets, cell containers | Chested animals can't travel; buckets are archived; cell containers lock view-only |
| Taking from the old world before Homecoming | Entering old land; reaching, hoppering or blowing up into it from the edge; withdrawing from the vault early | Block-mode zones refuse entry and every change across the edge; the vault is read-only until Homecoming |
| Building in old bases after Homecoming | Placing blocks, buckets, TNT | No-build mode |
| Using the Hall as a teleport or stash | `/hof`, then walk out; drop items inside; fill a cell chest | Return to the saved position on any exit; no drops or pickup; cell containers view-only after the lock; no hoppers in the Hall |
| Griefing someone's `/hof` return | Lava, fire or mobs at their saved spot while they're away | Return safety checks, then bed, nearby spot or spawn; 5 s immunity |
| Text injection | Formatting or click events in names, pet names, sign or book text | Player text is always a plain literal text component. No player text is ever parsed as formatting or put into a command. |
| Leaked secrets | Pasting config into Discord for support | Webhooks in secrets.yml or the environment; redacted in logs; never echoed by `/season config get` |
| Permission escalation | Default grants | Admin and curation nodes ops-only; bypass nodes denied to everyone by default |
| Accidents by ops | `/kill @e`, creative-mode edits in the Hall | Reconciliation repairs displays; audit log records it; `/hof restore` |
| Server lag | Many players or zones | Zone checks every 10 ticks, a tenth of players per tick; reconciliation batched per chunk; archive view paged |

**Tripwire.** Every item shown in an archive view or vault page carries custom data `seasons:archived`. The plugin checks for that tag on item pickup, on menu close and in a sweep of online players every 30 s. A tagged item found in a real inventory or on the ground is deleted, audited, and reported to the admin webhook.

**Reconciliation.** On chunk load and every 5 minutes, each cell's displays are compared with its records. Missing entities are respawned, unexpected tagged entities removed, mismatched equipment fixed, and each repair audited.

**Audit log** (`audit/YYYY-MM.jsonl`, one JSON object per line)

- Fields: time, actor, action, target, result, details.
- Logged: archive and unarchive, vault withdrawals, blocked Hall interactions, zone violations, tripwire hits, reconciliation repairs, recovery runs, config changes (path, old value, new value, who), museum changes, curation actions, pet registrations and travel, egg delivery, zone mode changes, Homecoming.
- Secrets are never written.

**Disclosure.** The repository includes `SECURITY.md` asking people to report dupes privately, not in public issues.

## Architecture and engineering standards

A game update should only ever touch one module. The domain logic is plain Java with no Minecraft imports, and a thin Seasons Adapter (Paper in v1) is the only code that calls the game.

&#91;embedded content: module architecture · core, adapter, integrations\]

The core defines ports (interfaces) for everything it needs from the game. The adapter implements them, and optional integrations plug into the same ports.

**Modules**

| Module | Contains | Depends on |
| --- | --- | --- |
| `seasons-api` | Seasons API: public events (`PhaseChanged`, `PlayerArchived`, `ZoneEntered`, `HomecomingStarted`, `SeasonFirst`, `DragonSlain`) and read-only queries for other plugins | Nothing |
| `seasons-core` | State machine, archive and vault policy, advancement snapshots, zone geometry and decisions, pet rules, firsts and awards, unlock evaluation, config records and validation, journal, audit model, the port interfaces | `seasons-api` |
| `seasons-adapter-paper` | The Paper Seasons Adapter: port implementations, Brigadier commands through Paper's Commands API, event listeners, item storage with serializeAsBytes | Core, Paper API |
| `seasons-integrations` | BlueMap, Discord webhooks, each enabled only when present | Core |
| `seasons-web` | The separate Seasons Web plugin: HTTP server on virtual threads, device-code login, sessions, the read-only API, the dashboard page, and its `/web` commands | `seasons-api`, Paper API |
| `seasons-testkit` | The platform-neutral scenario suite (Mineflayer bots plus admin commands) and the small test plugin that drives it | Nothing at runtime |

**Repository layout**

```
seasons/
  api/  core/  integrations/  web/  testkit/
  adapters/
    paper/              # v1; fabric/ and neoforge/ later
  templates/            # default cell templates (alcove, plinth)
  docs/                 # config reference, adr/, paper-api.md, runbook
  .github/workflows/    # build.yml, release.yml
  SECURITY.md  CONTRIBUTING.md  CHANGELOG.md  LICENSE   # PolyForm Noncommercial 1.0.0
```

`core/`, `adapters/paper/` and `integrations/` build the Seasons plugin jar for Paper, and `web/` builds the Seasons Web plugin jar.

**Version resilience**

- Use only the public Paper API: no server internals, no reflection, no paperweight-userdev. Every API the adapter calls is listed in `docs/paper-api.md`. The three spots that needed mixins on Fabric use plain API here: `DragonBattle#setPreviouslyKilled`, portal events, and teleport events with the ender pearl cause.
- Use typed API (registry keys, entity setters, Adventure components), never raw NBT or command strings, so game changes surface as compile errors.
- Persist items with `ItemStack#serializeAsBytes`, which records the `DataVersion`. Paper upgrades older items when they load.
- v1 supports 26.3 only. From the next drop on, support the latest drop and the one before it from one branch. Compile against the oldest supported Paper API. CI also compiles against the newest with deprecation warnings as errors, so API removals show up before they land.
- Avoid experimental Paper API, or keep it behind a port with a fallback. Paper 26.3 took the experimental label off most of the data component API, which Seasons uses to describe archived items as text for Seasons Web.

**Runtime rules**

- All world changes happen on the server thread. File I/O runs on one background writer, and the journal is fsynced before the world change it covers.
- Constructor injection throughout. No static singletons apart from entrypoint wiring.
- Zone checks run every 10 ticks and reconciliation is batched per chunk. Nothing scans every loaded entity each tick.

**Threading by feature.** The server thread is the only thread that changes the world, players or entities. Other threads do the slow work on read-only snapshots and hand results back through the server's task queue. Nothing on the server thread ever waits on a lock.

| Feature | Where it runs |
| --- | --- |
| Zone checks | Server thread, spread so each tick checks a tenth of online players |
| Archiving | Server thread copies the inventory; a worker writes and fsyncs the snapshot; the server thread clears the inventory once it is safely written |
| Rendering displays | Server thread, a few cells per tick, so rollover causes no lag spike |
| Reconciliation | Server thread on chunk load, with a budget of about 1 ms per tick |
| Journal and audit log | One writer thread, so entries stay in order |
| Seasons Web, Discord, BlueMap | Their own threads, reading only the latest snapshot, with bounded queues and backoff on retries |
| Stats for offline players | A worker reads them from disk |

Target: well under 1 ms per tick on average, measured with the spark profiler. A test helper fails any end-to-end test that touches game state off the server thread.

**Code standards**

- Java 25: records for data, sealed interfaces for phases and results.
- JSpecify annotations with NullAway, ErrorProne, and Spotless formatting, all enforced in CI.
- SLF4J logging with structured context. Secrets are never logged.
- Javadoc on the public API. Every behaviour change gets a changelog entry.

**Testing**

| Layer | Covers |
| --- | --- |
| Unit tests (core with JUnit; listeners and commands with MockBukkit) | State transitions, unlock rules, zone geometry, config validation and migrations, journal recovery decisions |
| End-to-end (real Paper servers, Mineflayer bots as players) | Archiving end to end; crash recovery at each journal step; each archive-view click type; vault withdrawal with a full inventory; zone enforcement by walking, elytra, pearl, portal and boat; `/hof` entry and return; reconciliation; import |
| Rehearsal | A full rollover on a copy of the real world with Mineflayer bots, before each live season |

**CI and releases**

- GitHub Actions: build, unit tests and end-to-end tests on each supported game version.
- Tagged releases publish to Hangar, Modrinth and GitHub Releases.
- Semantic versioning for both plugins. Config and data formats carry their own version numbers and migrations.

**Documentation.** A README, a config reference generated from the code, decision records in `docs/adr/` (for example why zones instead of the vanilla world border, and why no inventory-GUI library in v1), the runbook, CONTRIBUTING and SECURITY. Seasons, Seasons Web and seasons-api are source-available under the PolyForm Noncommercial License 1.0.0 (see Licence in the Overview).

## Paper implementation

How the design maps onto Paper. Everything here is public Paper API. `docs/paper-api.md` lists every API the adapter calls, so each drop's API changelog can be checked against it in a few minutes.

### Plugin setup

- Each jar ships a `paper-plugin.yml` with `api-version: '26.3'` and no `plugin.yml`.
- Seasons declares BlueMap as an optional server dependency (`required: false`, `load: BEFORE`), so BlueMap's API is on the classpath when it is installed. LuckPerms needs no declaration, because Seasons only calls `hasPermission`.
- Seasons registers its API with Bukkit's `ServicesManager`. Seasons Web and any other plugin fetch it from there.
- Seasons Web declares Seasons as a required server dependency (`required: true`, `load: BEFORE`, `join-classpath: true`), so Paper always enables Seasons first.
- Permission nodes and their defaults are declared in `paper-plugin.yml`: player nodes `true`, admin and curation nodes `op`, bypass nodes `false`. Bukkit has no op levels, so the old level 3 and 4 fallbacks become "ops".
- Configurate is shaded and relocated into the jar rather than downloaded at startup. The plugin works offline, and Gradle dependency verification pins the exact library it contains.
- bStats metrics (bstats-bukkit) are shaded and relocated into Seasons' own package, with one plugin id per jar. They send what bStats always does (server count, game version, server software, Java version) plus a few anonymous charts: whether Seasons Web is installed, the cell assignment mode, the Hall access mode, and how many seasons a server has run. No player names, coordinates or addresses. Server owners turn bStats off for every plugin in plugins/bStats/config.yml.

### Build

- Gradle with the Kotlin DSL and a Java 25 toolchain. `paper-api` is `compileOnly`, pinned to the oldest supported drop with the build number kept in the version pattern (for example `26.3.build.+`), as Paper advises, so a patch release can't sneak in.
- No paperweight-userdev and no server internals. If a future feature ever needs internals, it goes into its own optional module, never into `seasons-adapter-paper`.
- [run-paper](https://github.com/jpenilla/run-task) (`./gradlew runServer`) starts a local Paper server of any supported version with the plugins installed.
- Shadow builds the jars; `paper-plugin.yml` is hand-written, with the version filled in at build time.

### Feature map

| Feature | Paper API | Notes |
| --- | --- | --- |
| Commands | Brigadier through Paper's `Commands` API, registered in the `LifecycleEvents.COMMANDS` event | Tab completion and permission checks per node, so players only see what they can run |
| Permissions | `Permissible#hasPermission`, with nodes declared in `paper-plugin.yml` | Works with LuckPerms or any permissions plugin |
| Messages | Adventure components and MiniMessage, both built into Paper | `messages.yml` strings are MiniMessage, so colours and hover text need no code |
| Capturing gear | `PlayerInventory` (storage, armour, off hand), `Player#getEnderChest`, experience and effects | Copied on the main thread in one tick |
| Storing items | `ItemStack#serializeAsBytes` and `ItemStack.deserializeBytes` | Vanilla item data with its DataVersion, upgraded when a newer server loads it |
| Advancements | `Bukkit.advancementIterator`, `Advancement#getDisplay`, `Player#getAdvancementProgress`, `AdvancementProgress#getAwardedCriteria`, `revokeCriteria` and `awardCriteria` | Visible means it has a display; recipe unlocks are skipped |
| Season firsts | `PlayerAdvancementDoneEvent`; `PlayerChangedWorldEvent` for the Nether and End; the 30 s inventory sweep for rare items | First per key per season, recorded once |
| Archive, curator and cell-container views | Inventories owned by a Seasons `InventoryHolder`; `InventoryOpenEvent` to swap in the view-only copy; `InventoryClickEvent`, `InventoryDragEvent`, `InventoryCloseEvent` | Every click cancelled, then `Player#updateInventory` |
| Tripwire tag | Persistent data container on items (`seasons:archived`) | Checked on `EntityPickupItemEvent`, `InventoryCloseEvent` and the 30 s sweep |
| Figures | `Mannequin` entity: profile (skin), equipment, pose; armour stand with a player head as the fallback | Each display carries its cell id in persistent data, so reconciliation finds it |
| Plaques and trophies | `TextDisplay`, `ItemDisplay` and item frames |  |
| Season card | A written book through Adventure's `Book`, on a lectern or opened with `Player#openBook` |  |
| Teleports | `Player#teleportAsync` with a teleport cause | Loads the destination chunk without stalling the tick |
| Return safety | Material checks at feet, head and below; `World#getNearbyEntities` filtered to monsters; `Player#getRespawnLocation` for the bed | Falls back to bed, then the nearest safe block, then spawn |
| Pets | `Tameable#getOwnerUniqueId`, `ChestedHorse#isCarryingChest`, `EntitiesUnloadEvent` for the last known position, `Entity#getRemovalReason` (26.3) to tell a pet that unloaded from one that died, `World#getChunkAtAsync`, `Server#getEntity`, `Entity#teleportAsync`, `Entity#leaveVehicle`, `LivingEntity#setLeashHolder(null)` | Load the chunk, find the pet by id, then move it |
| Furthest block | `BlockPlaceEvent` | One distance comparison per placement |
| Zone checks | A repeating task over online players, a tenth per tick | As in the threading table |
| Zone teleports and portals | `PlayerTeleportEvent` (ender pearl, chorus fruit, commands), `PlayerPortalEvent`, `EntityPortalEvent`, `VehicleMoveEvent` | Replaces the three Fabric mixins |
| No-build zones | `BlockPlaceEvent`, `BlockBreakEvent`, `PlayerBucketEmptyEvent`, `PlayerBucketFillEvent`, `HangingPlaceEvent`, `HangingBreakByEntityEvent`, EntityPlaceEvent, EntityBreakByEntityEvent (cushions), `PlayerInteractEvent` (TNT, flint and steel), `EntityExplodeEvent` and `BlockExplodeEvent` (block lists cleared), `BlockBurnEvent`, `BlockIgniteEvent` |  |
| Hall containers | `BlockPlaceEvent` refuses hoppers, droppers, dispensers and crafters in the Hall; `InventoryMoveItemEvent` refuses any move touching a Hall container |  |
| Frontier outline (optional) | `Server#createWorldBorder` and `Player#setWorldBorder` | Only for `outside` circle zones; TheRanch doesn't use it |
| Hall sealing | Block break and place, interact, entity damage, hanging break, cushion place and break, explosion, piston and item drop events | Deny by default inside Hall bounds |
| Dragon fight | `World#getEnderDragonBattle`, `DragonBattle#setPreviouslyKilled(false)`, `DragonBattle#getEndPortalLocation`; `EntityDamageByEntityEvent` for participants; `EntityDeathEvent` with its damage source's causing entity for the slayer |  |
| Egg delivery | After the kill, check the block above the exit portal every second for up to 30 s; when the egg appears, set it to air and give the item | Falls back to the Returned vault page |
| Cell templates | `StructureManager` loads `.nbt` templates; DATA structure blocks in the palette give anchor metadata |  |
| Stats | `Player#getStatistic`; the stats files for offline players | Snapshot at launch, differences at archive time |
| Deaths display | A scoreboard objective in the list slot |  |
| Login catch-up | `AsyncPlayerPreLoginEvent` loads the player's record off the main thread; `PlayerJoinEvent` applies it | Joins never wait on disk |
| BlueMap | `BlueMapAPI.onEnable`, marker sets, shape markers for zones | Unchanged from the Fabric plan |

### Threading on Paper

- The threading rules above stand. Paper's main thread is the server thread, and Paper itself refuses many world changes made off it.
- Use Paper's schedulers that also exist on Folia (`GlobalRegionScheduler`, `EntityScheduler`, `AsyncScheduler`) instead of the older `BukkitScheduler`. On Paper they behave as usual; using them now keeps a later Folia port small.
- File and network work stays on Seasons' own virtual-thread executor and single journal writer.

**Folia** is not supported in v1. Folia runs regions on separate threads, while Seasons moves players between distant regions (Hall at 0,0, spawn far out) and keeps one global season state. The scheduler choice above keeps that door open.

### Testing and release

- Core unit tests are unchanged. [MockBukkit](https://github.com/MockBukkit/MockBukkit) covers listener and command tests; it is published per game version and can trail a new drop, so it never blocks a release.
- End-to-end: CI starts real Paper servers with a small test plugin that runs the scenarios and reports each one's pass or fail. run-paper downloads Paper and the Via plugins; the scenario shard runner in `buildSrc` launches the servers, several side by side (see The scenario suite under Development environment and repository). [Mineflayer](https://github.com/PrismarineJS/mineflayer) bots join those offline-mode servers to play the player side: logging in mid-rollover, clicking every slot of the archive view, throwing pearls at a zone edge.
- Tagged releases publish to [Hangar](https://hangar.papermc.io/) (Paper's plugin repository), Modrinth and GitHub Releases from GitHub Actions.

## Multi-platform design (Paper first)

Seasons ships for Paper first, but the code is shaped so adapters for Fabric and NeoForge can follow without a rewrite. This section is the contract that keeps that true. Nothing is built for other platforms in v1; what is built is the boundary.

**Names**

- **Seasons API** (`seasons-api`): the public API. Platform-neutral.
- **Seasons core** (`seasons-core`): all season logic. Plain Java, with no Minecraft or platform imports.
- **Seasons Adapter** (`seasons-adapter-<platform>`): implements the core's ports on one platform. `seasons-adapter-paper` ships in v1; `seasons-adapter-fabric` and `seasons-adapter-neoforge` can follow.
- Each published jar is core plus one adapter plus integrations: "Seasons" for Paper, and later "Seasons for Fabric" and "Seasons for NeoForge".
- **Seasons Web** depends only on Seasons API, so its logic is the same everywhere. Only its small entry point and command registration differ by platform.

**Ports** (interfaces the core owns and every adapter implements)

| Port | Responsibility | In the Paper adapter |
| --- | --- | --- |
| `Players` | Online players, profiles and skins, game mode, health, XP, effects, respawn point, messages, titles, boss bars | Player API, Adventure |
| `Inventories` | Read, clear and restore inventory, ender chest, cursor and crafting grid, as neutral item records | `PlayerInventory`, `serializeAsBytes` |
| `Advancements` | List visible advancements, read progress, revoke, restore | Advancement API |
| `World` | Blocks, containers, chunk loading, positions, dimensions, biomes, spawn | World and Block API |
| `Entities` | Find by id, teleport, tamed owner, pack-animal chests, last known position | `Entity`, `Tameable`, `ChestedHorse` |
| `Displays` | Figures, text and item displays, books | `Mannequin`, `ArmorStand`, display entities |
| `Menus` | View-only and withdraw-only menus with every click type refused | Inventory API with a Seasons holder |
| `Commands` | One command tree described by the core; the adapter registers it | Brigadier through Paper's Commands API |
| `Permissions` | Permission checks and node defaults | Bukkit permissions |
| `Events` | Turns platform events into core intents (move, place, break, open container, move items, pickup, portal, teleport, damage, death, advancement) and applies the core's decision | Paper listeners |
| `Scheduler` | Run for a player, an entity, a location or globally; run later; run off-thread | Paper's region-aware schedulers |
| `DragonFight` | Previously-killed flag, exit portal location | `DragonBattle` |
| `Statistics` | A player's statistics, online or from disk | Statistic API, stats files |
| `Storage` | Paths for config and world data | Plugin data folder, world folder |

**Rules every adapter keeps**

1. **Neutral data on disk.** Archives, vaults and records hold items as vanilla item data (an NBT compound with its `DataVersion`), never a platform format. Paper's `serializeAsBytes` writes that; a Fabric adapter would read and write the same bytes with the game's own item codec and data fixer. A CI test opens every archive the Paper adapter writes with a plain NBT reader, to keep this honest. A server can switch platforms and keep its Hall.
2. **The core never assumes a thread.** It asks the Scheduler port to run work for a player, an entity, a location or globally. On Paper, Fabric and NeoForge those are all the main thread; on Folia they are regions. Nothing in the core blocks on game state.
3. **Decisions, not events.** The core never sees a platform event. Adapters turn events into intents and apply the core's decision: allow, deny, redirect, or deny with a message. The protection rules live once, in the core.
4. **Capabilities, declared.** Each adapter declares what it can do: mannequins, per-player world borders, region threading, protection coverage for world changes made by other mods. The core degrades where one is missing (armour-stand figures, for example), and `/season status` lists any gaps.
5. **One scenario suite.** The end-to-end scenarios drive the server only through Mineflayer bots and admin commands, so the same suite runs unchanged against every adapter. An adapter is done when it passes the whole suite on its supported versions.
6. **Shared libraries only where they span platforms:** Configurate for YAML, Adventure for text (built into Paper, through its mod platform on Fabric and NeoForge), Brigadier for commands.

**What stays in each adapter**

- Protection coverage. Paper fires events for nearly every world change. On Fabric and NeoForge the adapter hooks the game itself, and other mods can still change blocks without telling anyone; the adapter's capability list says so.
- Displays and menus, built from each platform's own entity and screen code.
- Packaging: `paper-plugin.yml` for Paper; `fabric.mod.json` or `neoforge.mods.toml` later.

**Versions are a separate concern.** Each adapter supports the latest drop and the one before it. If an adapter ever has to span more versions, version-specific code lives in small shims inside that adapter (Stonecutter for Fabric and NeoForge), never in the core.

**When to add an adapter.** Only when a real server asks for one, because each adds a full test matrix and release work. The order would be Fabric (the same unobfuscated game code, with mixins for the few missing hooks), then NeoForge. Folia support would come through the Paper adapter, once rule 2 is proven by the scenario suite on Folia.

## Development environment and repository

This section gets a new repository from empty to tagged releases with jars attached. Whoever builds Seasons, a person or a coding agent, works from this spec saved as `docs/spec.md` (export it from Docs as Markdown). It is the source of truth.

**Local machines**

- **MacBook (Apple Silicon, Homebrew), the dev machine:** `brew install --cask temurin@25` (it lands in `/Library/Java/JavaVirtualMachines/temurin-25.jdk`), then `brew install node gh` and `gh auth login` once.
- **CachyOS with Nix home-manager (optional):** the flake below gives the same tools with `nix develop`, or direnv with `use flake`.
- **IDE: IntelliJ IDEA Ultimate.** Open the repository folder and it imports the Gradle build. Set both the project SDK and the Gradle JVM to temurin-25, and run `runServer`, `build` and `scenarioTest` from the Gradle tool window. The optional Minecraft Development plugin adds Minecraft-aware inspections.
- **Minecraft:** a Java Edition 26.3 client, to join the local test server at `localhost`.
- **Coding agent:** Claude Code (or another) runs in the repository root and reads `AGENTS.md` through `CLAUDE.md`.

```nix
# flake.nix
{
  description = "Seasons dev shell";
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-darwin" ];
      forAll = f: nixpkgs.lib.genAttrs systems (s: f nixpkgs.legacyPackages.${s});
    in {
      devShells = forAll (pkgs: {
        default = pkgs.mkShell {
          packages = [ pkgs.jdk25 pkgs.nodejs_24 pkgs.git pkgs.gh ];
          JAVA_HOME = "${pkgs.jdk25}";
        };
      });
    };
}
```

For the Nix machine only: `.envrc` holds one line, `use flake`. If your nixpkgs has no `jdk25`, use `temurin-bin-25`.

**Repository bootstrap** (the first commit of M0, on top of the repository layout in Architecture)

```text
settings.gradle.kts         # include(":api", ":core", ":integrations", ":adapters:paper", ":web", ":testkit")
build.gradle.kts            # Java 25 toolchain for every module, SPDX header check
gradle/libs.versions.toml   # every dependency and plugin version, pinned
gradle/wrapper/             # committed Gradle 9 wrapper
gradle.properties           # version=1.0.0-SNAPSHOT
flake.nix  .envrc           # optional: the Nix dev shell
AGENTS.md  CLAUDE.md        # CLAUDE.md is one line: @AGENTS.md
LICENSE  THIRD_PARTY_NOTICES.md  README.md  CHANGELOG.md  CONTRIBUTING.md  SECURITY.md
docs/spec.md  docs/paper-api.md  docs/questions.md  docs/adr/
.github/workflows/build.yml  .github/workflows/nightly.yml  .github/workflows/release.yml
.github/scripts/docs-only.sh  .github/dependabot.yml
buildSrc/                   # the scenario shard runner: plain Java on the Gradle API
.gitignore                  # build/, .gradle/, run/, .idea/, .direnv/, secrets.yml, *.jar
```

**Dependencies.** Pick the latest stable version of each at M0 and pin it in `libs.versions.toml`; Dependabot proposes updates after that.

| Purpose | Coordinates | Scope |
| --- | --- | --- |
| Paper API | `io.papermc.paper:paper-api:26.3.build.+` from `https://repo.papermc.io/repository/maven-public/` | compileOnly |
| Fat jars | Gradle plugin `com.gradleup.shadow` | build |
| Local server | Gradle plugin `xyz.jpenilla.run-paper`, Minecraft 26.3 | build |
| Config | `org.spongepowered:configurate-yaml` | shaded, relocated |
| Metrics | `org.bstats:bstats-bukkit` | shaded, relocated |
| Map | BlueMap API 2.x (see BlueMap's docs for the repository) | compileOnly |
| Tests | JUnit 5, AssertJ, MockBukkit for the newest version it supports | test |
| Bots | npm `mineflayer`, in `testkit/bots/` with a committed `package-lock.json` | test |

The shaded jars are the only jars: disable the plain `jar` task so `build/libs` holds `Seasons-<version>.jar` (from `adapters/paper`) and `SeasonsWeb-<version>.jar` (from `web`).

**Everyday commands**

```bash
./gradlew build                       # compile, unit tests, both jars
./gradlew :adapters:paper:runServer   # Paper 26.3 in run/ with both plugins installed
./gradlew scenarioTest                # Paper servers plus Mineflayer bots run the acceptance scenarios
./gradlew scenarioTest -Pscenarios=a33-homecoming,m3-*   # just those scenarios
```

**The scenario suite.** `scenarioTest` splits the scenarios into shards, longest first by the committed `testkit/scenario-timings.json`, and runs the shards side by side, each on its own Paper server with its own port and folder: as many as the machine has two cores and 2 GB of memory for, or `-PscenarioShards=<n>`. A shard's server boots once and runs its scenarios one after another, in an order shuffled with a printed seed (`-PscenarioSeed=<n>` repeats it). Between scenarios the test plugin resets the server to a fresh boot (Seasons' state in memory and on disk, players and their files, scoreboards, rules, every block touched in every dimension, entities) and checks it matches the fingerprint of the shard's fresh boot; a difference fails the scenario before it, loudly. Scenarios with sessions or crash points still restart the server; a scenario the reset can't serve is marked `// @isolated <reason>` and gets a fresh server of its own. Scenarios don't wait out time: `/scenario clock` moves Seasons' clock past reminders, countdowns, windows, rate limits and cooldowns, and `/scenario sync` answers once the server has finished its tick, in place of fixed sleeps; only waits the game itself needs ticks for remain (ADR 0003). `-PscenarioIsolation=fresh` runs every scenario on a freshly wiped server of its own, as the nightly workflow does. Each run writes `build/reports/scenario-timings.md`; after adding or changing scenarios, refresh the balance with `./gradlew :testkit:updateScenarioTimings`. Targets: about 5 minutes for a pull request's CI, under a minute for one scenario, under 5 minutes for the full suite on the MacBook.

To test on real data, copy a backup of TheRanch's world into `run/world`, never the live world. `run/server.properties` sets `online-mode=false` so the bots can join; it never ships.

**GitHub**

1. `git init`, commit the bootstrap, then `gh repo create seasons --public --source . --push`. A public repository makes release downloads public; the licence still forbids selling. Make it `--private` until v1 if you prefer.
2. In the repository settings, protect `main`: pull requests only, and the `build` check must pass.
3. Every push and pull request runs `build.yml`: one job builds the jars and runs the unit tests, four shard jobs run the scenarios with those jars (Paper, its libraries and the Via jars cached between runs; Gradle's downloads saved by the jars job, so Maven Central isn't asked for everything six times at once), and a final job named `build` collects every shard's samples, runs the NBT neutrality test and fails unless every shard passed and every session of every scenario file has a passing result among them (`:testkit:scenarioCoverage`), so a shard that never ran can't pass unnoticed. The shard count is the matrix's length alone. A push that changes only documentation (`docs/`, Markdown files at the root, `LICENSE`; `.github/scripts/docs-only.sh`) skips the shards, and the final job passes that, and nothing else, as skipped. A newer push to the same branch cancels the run before it. `nightly.yml` runs the suite in fresh mode every night and on demand. Pushing a tag such as `v1.0.0` runs `release.yml`, which publishes a GitHub Release with both jars and their checksums.

```yaml
# .github/workflows/build.yml
name: build
on:
  push:
    branches: [main]
  pull_request:
permissions:
  contents: read
# A newer push to the same branch or pull request cancels the run before it.
concurrency:
  group: build-${{ github.ref }}
  cancel-in-progress: true
jobs:
  changes:
    runs-on: ubuntu-latest
    outputs:
      docs_only: ${{ steps.rule.outputs.docs_only }}
    steps:
      - uses: actions/checkout@v7
        with:
          fetch-depth: 0
      - name: Does this change only documentation?
        id: rule
        env:
          BASE: ${{ github.event_name == 'pull_request' && github.event.pull_request.base.sha || github.event.before }}
        run: |
          # The rule itself, and the paths that count as docs: .github/scripts/docs-only.sh
          if [ -z "$BASE" ] || [ "$BASE" = 0000000000000000000000000000000000000000 ]; then
            docs_only=false
          else
            docs_only=$(git diff --name-only "$BASE...$GITHUB_SHA" | .github/scripts/docs-only.sh)
          fi
          echo "docs_only=$docs_only" >> "$GITHUB_OUTPUT"
          echo "Docs-only change: $docs_only" >> "$GITHUB_STEP_SUMMARY"

  jars:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '25'
      - uses: gradle/actions/setup-gradle@v6
        with:
          cache-provider: basic
      # Gradle's downloads, shared with the shard and final jobs (see their restore step).
      - id: deps
        uses: actions/cache/restore@v6
        with:
          path: |
            ~/.gradle/caches/modules-2
            ~/.gradle/wrapper/dists
          key: gradle-deps-${{ hashFiles('**/*.gradle.kts', 'gradle/libs.versions.toml', 'gradle/verification-metadata.xml', 'gradle/wrapper/gradle-wrapper.properties') }}
      - run: ./gradlew build
      - if: steps.deps.outputs.cache-hit != 'true'
        uses: actions/cache/save@v6
        with:
          path: |
            ~/.gradle/caches/modules-2
            ~/.gradle/wrapper/dists
          key: gradle-deps-${{ hashFiles('**/*.gradle.kts', 'gradle/libs.versions.toml', 'gradle/verification-metadata.xml', 'gradle/wrapper/gradle-wrapper.properties') }}
      - uses: actions/upload-artifact@v7
        with:
          name: seasons-dev-${{ github.sha }}
          path: |
            adapters/paper/build/libs/Seasons-*.jar
            web/build/libs/SeasonsWeb-*.jar
      - uses: actions/upload-artifact@v7
        with:
          name: scenario-jars-${{ github.sha }}
          retention-days: 3
          path: |
            adapters/paper/build/libs/Seasons-*.jar
            web/build/libs/SeasonsWeb-*.jar
            testkit/build/libs/seasons-testkit-*.jar

  shard:
    needs: [changes, jars]
    if: needs.changes.outputs.docs_only != 'true'
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        # The shard count comes from this list alone (strategy.job-total); the final job checks
        # that the shards between them ran every scenario.
        shard: [1, 2, 3, 4]
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '25'
      - uses: actions/setup-node@v7
        with:
          node-version: '24'
      - uses: gradle/actions/setup-gradle@v6
        with:
          cache-provider: basic
      # Gradle's downloads, saved by the jars job: the other jobs start with them instead of all
      # fetching every dependency from Maven Central at once, which it rate-limits (HTTP 429).
      - uses: actions/cache/restore@v6
        with:
          path: |
            ~/.gradle/caches/modules-2
            ~/.gradle/wrapper/dists
          key: gradle-deps-${{ hashFiles('**/*.gradle.kts', 'gradle/libs.versions.toml', 'gradle/verification-metadata.xml', 'gradle/wrapper/gradle-wrapper.properties') }}
      - uses: actions/download-artifact@v8
        with:
          name: scenario-jars-${{ github.sha }}
          path: scenario-jars
      - id: month
        run: echo "month=$(date -u +%Y-%m)" >> "$GITHUB_OUTPUT"
      # Paper's jar and the Via jars (run-paper's cache), and Paper unpacked with its libraries.
      # A new month or a version change starts a new cache, which picks up newer Paper builds.
      - uses: actions/cache@v6
        with:
          path: |
            ~/.gradle/caches/run-task-jars
            testkit/build/scenario/*/bundler
          key: paper-${{ steps.month.outputs.month }}-${{ hashFiles('gradle.properties', 'gradle/libs.versions.toml') }}
          restore-keys: paper-
      - name: Scenario shard ${{ matrix.shard }} of ${{ strategy.job-total }}
        run: ./gradlew :testkit:scenarioTest -PscenarioShards=${{ strategy.job-total }} -PscenarioShard=${{ matrix.shard }} -PscenarioJars=scenario-jars
      - if: always()
        uses: actions/upload-artifact@v7
        with:
          name: scenario-results-${{ matrix.shard }}
          retention-days: 7
          path: testkit/build/scenario-results

  # The check branch protection requires. It runs whatever happened before it, and passes only if
  # the jars built and every shard passed, or every shard was skipped for a docs-only change: a job
  # skipped because something it needs failed would otherwise count as passing. After passing
  # shards, every session of every scenario file must have a passing result in their results.
  build:
    needs: [changes, jars, shard]
    if: always()
    runs-on: ubuntu-latest
    steps:
      - name: Every shard passed, or all were skipped for a docs-only change
        env:
          CHANGES: ${{ needs.changes.result }}
          DOCS_ONLY: ${{ needs.changes.outputs.docs_only }}
          JARS: ${{ needs.jars.result }}
          SHARDS: ${{ needs.shard.result }}
        run: |
          echo "changes: $CHANGES (docs only: $DOCS_ONLY), jars: $JARS, shards: $SHARDS"
          [ "$CHANGES" = success ] || { echo "The docs-only check didn't run"; exit 1; }
          [ "$JARS" = success ] || { echo "The jars didn't build"; exit 1; }
          if [ "$DOCS_ONLY" = true ] && [ "$SHARDS" = skipped ]; then
            echo "Docs-only change: the scenario shards were skipped" >> "$GITHUB_STEP_SUMMARY"
            exit 0
          fi
          [ "$SHARDS" = success ] || { echo "A scenario shard didn't pass"; exit 1; }
      - if: needs.shard.result == 'success'
        uses: actions/checkout@v7
      - if: needs.shard.result == 'success'
        uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '25'
      - if: needs.shard.result == 'success'
        uses: gradle/actions/setup-gradle@v6
        with:
          cache-provider: basic
      - if: needs.shard.result == 'success'
        uses: actions/cache/restore@v6
        with:
          path: |
            ~/.gradle/caches/modules-2
            ~/.gradle/wrapper/dists
          key: gradle-deps-${{ hashFiles('**/*.gradle.kts', 'gradle/libs.versions.toml', 'gradle/verification-metadata.xml', 'gradle/wrapper/gradle-wrapper.properties') }}
      - if: needs.shard.result == 'success'
        uses: actions/download-artifact@v8
        with:
          pattern: scenario-results-*
          merge-multiple: true
          path: testkit/build/scenario-results
      - if: needs.shard.result == 'success'
        name: Every scenario passed in some shard, NBT neutrality of every shard's samples and archives, and the run's timings
        run: |
          ./gradlew :testkit:scenarioCoverage :testkit:nbtNeutralityTest :testkit:scenarioTimings
          cat build/reports/scenario-timings.md >> "$GITHUB_STEP_SUMMARY"
```

```yaml
# .github/workflows/release.yml
name: release
on:
  push:
    tags: ['v*']
permissions:
  contents: write
jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v6
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '25'
      - uses: gradle/actions/setup-gradle@v6
        with:
          cache-provider: basic
      - name: Check the tag matches gradle.properties
        run: |
          v=$(grep '^version=' gradle.properties | cut -d= -f2)
          test "v$v" = "$GITHUB_REF_NAME" || { echo "tag $GITHUB_REF_NAME, version $v"; exit 1; }
      - run: ./gradlew build
      - name: Collect the jars
        run: |
          mkdir dist
          cp adapters/paper/build/libs/Seasons-*.jar web/build/libs/SeasonsWeb-*.jar dist/
          cd dist && sha256sum *.jar > SHA256SUMS
      - uses: softprops/action-gh-release@v3
        with:
          files: dist/*
          generate_release_notes: true
          prerelease: ${{ contains(github.ref_name, '-') }}
```

```yaml
# .github/dependabot.yml
version: 2
updates:
  - package-ecosystem: github-actions
    directory: /
    schedule:
      interval: monthly
  - package-ecosystem: gradle
    directory: /
    schedule:
      interval: monthly
```

Notes on the workflows: `cache-provider: basic` keeps Gradle's open-source cache and avoids the separately licensed caching component in `setup-gradle` v6. The release needs no secrets beyond the built-in token. For stricter supply-chain hygiene, pin each action to a commit hash; Dependabot updates hashes too. Hangar and Modrinth uploads join `release.yml` at M6, with their API tokens stored as repository secrets.

**Releasing.** Set `version=1.0.0` in `gradle.properties`, merge to `main`, wait for the build to pass, then `git tag v1.0.0 && git push origin v1.0.0`. The jars appear under Releases within a few minutes. A tag with a hyphen, such as `v1.1.0-beta.1`, becomes a pre-release. Afterwards set the version to the next `-SNAPSHOT`.

**AGENTS.md** (the agent's standing instructions)

```markdown
# Seasons: instructions for coding agents
- docs/spec.md is the source of truth. Don't change a decision in it. If something is ambiguous, take the safest reading, note it in docs/questions.md, and carry on.
- Build milestone by milestone, M0 to M7. A milestone is done when its acceptance scenarios pass in CI.
- Branch per milestone or feature, small commits with conventional messages, a pull request into main. Never push to main directly.
- Commands: ./gradlew build, ./gradlew scenarioTest, ./gradlew :adapters:paper:runServer.
- While working, run the unit tests and only the scenarios you touch (./gradlew scenarioTest -Pscenarios=<name>,<prefix>-*). Run the full suite once in the background before pushing. Don't wait for CI after pushing.
- Public Paper API only: no server internals, reflection or paperweight-userdev. List every Paper API you call in docs/paper-api.md.
- Only the server thread changes the world. Journal before every world change. No real archived item ever exists in the world.
- Player-facing text goes in messages.yml; settings in config files through Configurate records.
- Every source file starts with: // SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
- Never commit secrets, worlds or jars. Webhook URLs live in secrets.yml, which is gitignored.
- No dependency beyond the spec's list without a short record in docs/adr/.
```

## Acceptance scenarios

These are the behaviours the finished product must show. Each is an end-to-end test (Mineflayer bots plus admin commands on a real Paper server) unless marked as a unit test. A milestone is done when its scenarios pass on every supported version.

**Ending and launch**

- A1. With a planned end set, reminders arrive at 7 days, 1 day, 1 hour and 10 minutes, in chat and on Discord, and nothing ends at the planned time while `autoEnd` is false.
- A2. `/season end` starts a 10-minute boss-bar countdown; `/season cancel` during it changes nothing; `--now` skips it; a second `/season end` during a countdown is refused.
- A3. At the end of the countdown every gallery locks before the first player is archived, and an owner standing in their cell switches to adventure mode first.
- A4. Every online player ends in their own cell with an empty inventory and ender chest, zero XP, no advancements except recipe unlocks, no respawn point and full health.
- A5. A player offline at season end is archived at their next login: into their cell during Intermission, straight to the new spawn after launch.
- A6. `/season launch` teleports everyone online to the new spawn in survival with 5 s of immunity, sets the world spawn, resets the dragon flag, creates the new deaths objective and locks the old-world zones.

**Archive integrity**

- A7. Stopping the server at each journal step and restarting leaves every item exactly once: in the archive or on the player, never both and never neither. One test per step.
- A8. Every click type in the archive view (left, right, shift, number keys, off-hand swap, drop, drag, double-click, creative clone) moves nothing, and the client is resynced.
- A9. An item tagged `seasons:archived` found on the ground or in a real inventory is deleted within 30 s and audited.
- A10. Every archive file opens with a plain NBT reader and holds a `DataVersion`. (Unit test.)
- A11. `/season unarchive` restores items and advancements and removes the displays.

**Advancements and stats**

- A12. A player's advancements at season end are in their archive, the plaque shows "completed / visible total", and their advancement screen is empty afterwards.
- A13. The first player to complete an advancement in a season is broadcast as a season first; the second is not.
- A14. The season card has the highlights, every General-tab statistic as a season difference, the player's firsts and their furthest block.
- A15. Placing a block 30,000 blocks from spawn updates the player's furthest block; placing one in the Hall doesn't.

**Pets**

- A16. `/pet add` works on the player's own tamed wolf, and is refused on a wild wolf, a villager, someone else's cat and a donkey with a chest.
- A17. After launch, a registered pet whose chunk was unloaded arrives next to its owner at the new spawn; an owner offline at launch gets their pets at first login.
- A18. A registered donkey that has a chest at travel time stays behind, and the owner is told why.
- A19. A registered pet that died is reported and its registration dropped.

**Hall and cells**

- A20. Opening the decorating window gives everyone who played the season a random free cell and tells them which; a player who first plays later gets one at next login.
- A21. Two players running `/hof swap` at each other within 60 s swap cells; one alone changes nothing.
- A22. During decorating, an owner can place and open a chest in their cell; another player sees a view-only copy and can take nothing.
- A23. A hopper can't be placed in the Hall, and no hopper or hopper minecart can pull from a Hall container.
- A24. After the lock, the owner's chest opens as a view-only copy for everyone, the owner included.
- A25. With access `open`, a visitor walks into any cell in adventure mode and can break, place or take nothing.
- A44. A visitor can sit on a cushion in someone else's cell, but can't place or break one there, or in a no-build zone.
- A45. Before Homecoming nobody can enter the old-world circle by any route; after it, a player can take a stack of Season 1 blocks from an old chest and build with it, and break and rebuild part of a Season 1 base.
- A46. After /season end, an owner can place and break blocks in their own cell for 15 minutes, then the cell locks; /season launch before then locks it at once.
- A47. On a server that has never run Seasons, /season init 2 then /season end archives everyone into the Season 2 gallery, and the imported Season 1 cells are untouched. A second /season init is refused.
- A48. Without logging in, the dashboard shows the season clock, who's online, the event feed, the map and the Hall, but no leaderboards or full profiles; logged-in responses carry Cache-Control: private, no-store.
- A49. Behind the tunnel, per-IP rate limits use CF-Connecting-IP; the same header sent from an address outside proxy.trusted is ignored.
- A50. Importing a Season 1 cell with an armour stand (gear and player head) and three chests makes one record with the same items, one archive page per chest, a mannequin wearing the gear with the head's skin, and three archive anchors. The chests and stand hold nothing real afterwards, and /hof import undo puts every item back where it was.
- A51. A cell whose stand has no head, or a head of someone else, is flagged by the dry run and skipped by the import until an admin assigns its owner.

**Visiting**

- A26. `/hof` works within 3 blocks of any bed (straw beds included), and is refused with the reason when a monster is near, after damage in the last 10 s, while falling or gliding, or in lava.
- A27. Leaving the Hall by walking, pearl, pushing or falling returns the player to their saved spot.
- A28. If lava was poured on the saved spot while the player was away, `/hof leave` puts them at their bed; if the bed is unsafe too, the nearest safe block within 8 blocks; failing that, the season spawn.
- A29. A creeper standing at the saved spot sends the player to their bed instead.

**Old world**

- A30. A player can't walk, fly, ride, pearl, chorus-teleport or portal into a locked base zone, and is put back at their last valid position with a message.
- A31. A player flies 40,000 blocks from spawn without being stopped.
- A32. The nether roof is reachable from day one.
- A33. `/season unlock homecoming` switches every zone to its afterUnlock mode in one tick (off by default, so players can build in old bases), with title, broadcast and Discord post.
- A34. In a no-build zone a player can open chests, use a farm and take normal items, but can't place, break, use buckets or light TNT, and an explosion there breaks nothing.
- A35. Before Homecoming, a player standing at the edge of a blocked zone can't break, place, open or use a bucket on anything inside it, and pistons, hoppers, liquids and explosions can't cross the edge.
- A36. The ender vault is read-only before Homecoming and withdraw-only for everything after it, collectibles included.

**Dragon egg and museum**

- A37. After launch the dragon can be respawned, and the first kill of the season gives the egg to the slayer, with title, fireworks, broadcast and Discord post.
- A38. With a full inventory, the slayer's egg goes to the Returned page of their vault.
- A39. At season end, the museum's egg plinth for that season shows a display egg with the slayer's name.

**Configuration and admin**

- A40. A broken YAML edit followed by `/season reload` leaves the previous config live and reports the file, line and message.
- A41. `/season end --dry-run` reports a cell shortfall, a spawn inside a zone and a spawn beyond 24,000 blocks of 0,0.
- A42. Every curation action needs a reason, writes an audit entry and tells the owner.

**Performance**

- A43. With 20 bots online, 30 zones and a full rollover, the average tick time Seasons adds stays under 1 ms (measured with spark), and rendering displays never adds more than 5 ms to a single tick.

**Edge cases to handle**

- A player disconnects mid-countdown, mid-archive or mid-teleport: the journal and login catch-up finish the job.
- A player's skin changes between seasons: each figure keeps the skin of its own season.
- The new spawn's chunk isn't generated in time: launch waits for it and shows progress, rather than teleporting anyone into the void.
- A registered pet is inside a locked zone at travel time: travel still works, because the plugin moves it.
- A pet is in a boat or on a lead: it is dismounted and unleashed before travel.
- The dragon dies with no player involved: the egg stays on the portal, as in vanilla.
- A curator returns an item while the owner is offline: it waits on the Returned page.
- A player renames themselves: records follow the UUID; plaques keep the name at archive time.
- Time-zone mistakes: every scheduled time carries its zone, and `/season status` shows the planned end in the server's zone and in UTC.

## Season 3 runbook for TheRanch

World preparation comes first. Upgrade the game, pick spawn candidates in unexplored land, let the group vote, pre-generate the winner, build Season 2's hallway, set up the addresses, and draw the old-world zone. Then rehearse the whole rollover on a copy before doing it live. Nothing in the overworld is wiped.

**About 3 weeks before**

- [ ] Season 3 runs on Minecraft 26.3 "Wilderness Bound", the newest stable drop; Paper 26.3 has stable builds. Its Dappled Forest biome and abandoned camps, like 26.2's [sulfur caves](https://xgamingserver.com/blog/minecraft-26-2-chaos-cubed-release-date-sulfur-caves-everything-new/), [only appear in newly generated chunks](https://minecraft.how/blog/post/minecraft-biome-updates-2026).
- [ ] Check every plugin and client mod has a 26.3 build before upgrading. BlueMap 5.24 or newer reads 26.3 worlds, and DiscordSRV and Xaero's maps have 26.3 versions; check LuckPerms, Chunky, MCA Selector and Map Link too.
- [ ] After the upgrade, check white-list in server.properties: 26.3 turns it on for newly created server configs.
- [ ] In Cloudflare DNS, add play.petepeterepeat.com (A record, DNS only) and its SRV record, as in Hosting at TheRanch.
- [ ] Install cloudflared on the server and add the tunnel's two public hostnames, map and seasons. Bind BlueMap to 127.0.0.1 and close the old 25572 port.
- [ ] Full backup: every dimension plus player data.
- [ ] Upgrade a copy first: an upgraded world can't be downgraded. If the world is still on 1.21.x, remember that [26.1 made large changes to the world data format](https://patchbot.io/games/minecraft/articles/59-minecraft-261). On Paper, [26.1 also moves the Nether and End](https://papermc.io/news/26-1) into `world/dimensions/minecraft/` and migrates the old `world_nether` and `world_the_end` folders on first start, so point backup scripts, MCA Selector and BlueMap's map configs at the new paths.
- [ ] Set BlueMap's live player options `hide-vanished: true` and `hide-sneaking: true`, and install BlueMapPlayerControl so players can `/bmpc hide`.
- [ ] Ask every player for the coordinates of builds far from the hub (questionnaire Q18), so the old-world circle covers them.

**About 2½ weeks before**

- [ ] Pick 2 to 4 spawn candidates 16,000 to 23,000 blocks from 0,0, each in a direction nobody has explored. BlueMap shows which land has already been generated. Land near cold biomes is where 26.3's Dappled Forest appears. From each candidate, check a stronghold is within reach with `/locate structure stronghold`.
- [ ] Pre-generate a 500-block preview around each candidate with Chunky, only after the upgrade, and add one BlueMap map per candidate bounded to that preview.
- [ ] Install Seasons on the live server (it can sit idle until the rollover) and run /season init 2, because Season 2 is the season in progress. Mark the candidates with `/season spawn candidate add <name>`, and post the previews and a poll on Discord. One spawn for everyone.
- [ ] In MCA Selector, on a copy, select the End's outer-island chunks with low InhabitedTime, away from any End farm or build, and delete them so end cities and elytras regenerate. Check the result on the copy, then repeat on the real world after a fresh backup.

**About 2 weeks before**

- [ ] `/season spawn set <winning candidate>`. Pre-generate around it to about 3,000 blocks with Chunky. Keep it unrendered on BlueMap until launch, and remove the other candidates' preview maps.
- [ ] Build hallway 2, Season 2's gallery, and tag or stamp one cell per player. It starts empty: the first automated rollover archives Season 2 into it, so it only needs to be ready before decorating starts. If building runs late, open decorating by hand with /season decorate open. Add the museum's egg wall with one `egg` trophy anchor per season.
- [ ] Convert Season 1's hallway with `/hof import` (see Importing Season 1's cells): on a copy first (dry run, import, check, walk the hallway), then on the live world after a fresh backup. Remove the old barrier blocks afterwards.
- [ ] Draw the old-world zones: stand at 0,0 and run `/season zone add old-world cylinder <radius>`, with the radius just past the furthest Season 1 or 2 build; add a box with the wand for any build outside it; then `old-nether` in the Nether at an eighth of the radius with `--max-y 127`, so the roof stays open. All are `block` until Homecoming and `off` after it. Leave at least 2,000 blocks between the circle's edge and the new spawn.
- [ ] Add the shared waypoints (hub, megaprojects) with `/season marker add`.
- [ ] Tell everyone to register their pets with `/pet add` and take chests off pack animals.

**1 week before**

- [ ] After the group's end vote, `/season schedule end <time>` at least a week out, for the live event. Reminders start.
- [ ] The decorating window opens 7 days before the planned end. Everyone gets a random cell; swaps by agreement.
- [ ] Move the original dragon egg from Harryz101's ender chest onto the museum's first egg plinth with `/museum add`.

**2 days before**

- [ ] Rehearse on a copy of the live world, with Mineflayer bots joining it in offline mode: `/season end`, Intermission, `/season launch`, pet travel, a forced Homecoming, a refused entry at the old-world edge, building in an old base after Homecoming, a vault withdrawal. Read the audit log afterwards.

**The day before**

- [ ] Full backup.
- [ ] Run `/season end --dry-run` on the live server and fix anything it reports.

**Rollover (the live event)**

- [ ] Everyone online at the agreed time. `/season end`: countdown, everyone is archived and wakes up in their cell, with 15 minutes to arrange it before the cells lock.
- [ ] Intermission: look around, announce the awards and firsts.
- [ ] Press the launch button. Everyone, with their pets, arrives at the new spawn.
- [ ] Show the new area on BlueMap, check `/season status` for pending players and pets, post on Discord.

**Homecoming (when the group votes)**

- [ ] `/season unlock homecoming`. Everything opens: build anywhere, old bases and megaprojects included, and vaults become withdraw-only.

**Before Season 4**

- [ ] Note the furthest block from 0,0 in `/season status`, and pick Season 4's candidates in a different unexplored direction, still within 24,000 blocks so strongholds are in reach.

## Milestones and open decisions

Season 3 needs M0 to M3. The rest can follow during the season. Each milestone is done when its acceptance scenarios pass on every supported version.

1. **M0 Skeleton.** Repository bootstrap with the flake, AGENTS.md and the build and release workflows (see Development environment and repository), Gradle build, `paper-plugin.yml`, the ports wired to `seasons-adapter-paper`, run-paper, the CI harness with Mineflayer bots, the NBT neutrality test. Done when CI runs an empty scenario on every supported version.
2. **M1 Core rollover.** Season state and journal, planned end and reminders, manual end and countdown, archiving with advancements, furthest-block tracking, figure displays, archive view, teleports and login catch-up, launch, random cell assignment and swaps, cell tagging by command, legacy import, config and commands. Scenarios A1 to A15, A20, A21, A40, A46, A47, A50, A51.
3. **M2 Hall protection.** Sealing, walk-in cells, cell containers and the lock, `/hof` visits with return safety, deny-by-default rules, reconciliation, tripwire, audit log. Scenarios A22 to A29, A44.
4. **M3 Old world.** Zones with block, no-build and announce modes, Homecoming, the ender vault, pets and their travel, BlueMap areas and spawn candidates. Scenarios A16 to A19, A30 to A36, A41, A45.
5. **M4 Season extras.** Dragon egg to the slayer with fanfare, the egg wall, season cards with full stats, awards, season firsts, the museum, curation, Discord webhooks. Scenarios A37 to A39, A42. Can ship mid-season, ideally before anyone kills the dragon.
6. **M5 Cell tooling.** Wand, templates and stamping. Until this ships, cells are tagged by command.
7. **M6 Public release.** Full suite, CI across supported versions, performance scenario A43, generated config docs, Hangar and Modrinth listings.
8. **M7 Seasons Web.** The web server and API, device-code login, the connect page on the public site, the dashboard with the embedded BlueMap, profiles and second-screen mode. Scenarios A48, A49.
9. **Later: Seasons Adapters.** Fabric, then NeoForge, when a real server asks, each passing the same scenario suite. Bedrock players through Geyser if TheRanch wants them, after checking how mannequins and the archive view look to them.

**Open decisions**

- [ ] Answers from the other five players may still change the award list.

## Decision log

Player feedback so far. Q numbers refer to the questionnaire.

| Decision | Source |
| --- | --- |
| The season ends by group vote, with at least a week's notice, by hand at a live event | Taran Gross, Q1 to Q4 |
| Advancements are archived and reset every season; the plaque shows the count | Taran, Q10 |
| Tamed pets can be registered to travel with their owner at launch; pack animals without chests only | Taran, Q12 (travel at launch rather than summoning); limit of 5, Peter |
| Spawn 16,000 to 23,000 blocks out in unexplored land, not a million, so strongholds and the End stay reachable | Taran, Q15 and Q30 |
| The spawn is chosen by a group vote on BlueMap previews | Taran, Q16 |
| Nothing in the overworld is wiped | Taran, Q17 and Q18 |
| The old world is locked until Homecoming (no entering, building or taking), then the whole world unlocks; no distance limit elsewhere | Peter (Q19 and Q20) |
| The nether roof isn't locked | Peter, Q21 |
| Homecoming when the group votes, for everyone at once | Taran, Q22 and Q23 |
| After Homecoming everything is buildable, old bases included, so Season 1 and 2 megaprojects carry on | Peter, replacing Taran's no-build (Q25) |
| End: only untouched outer islands regenerate | Taran, Q26 |
| The dragon slayer gets the egg, with a fanfare; the museum has an egg plinth per season | Taran, Q28 and Q29 |
| Old collectibles unlock at Homecoming with everything else | Peter, replacing Taran's answer (Q30) |
| Cells lock at season end; visitors walk in, in adventure mode | Taran, Q34 and Q35 |
| Random cell assignment, swaps by agreement; any play time earns a cell | Taran, Q36 and Q37 |
| Containers allowed as cell decoration, view-only after the lock | Taran, Q38 |
| Safe-return checks for `/hof` | Taran, Q40 |
| Late joiners go straight to the new spawn | Taran, Q43 |
| Peter is the only curator | Taran, Q46 |
| Deaths in the tab list only | Taran, Q48 |
| Full statistics on the season card; firsts for every advancement and listed rare items; furthest-builder award | Taran, Q49 to Q51, and Q20 |
| Personal waypoints in Xaero, server-wide ones on BlueMap; no RLCraft-style waypoints | Taran and Peter, Q53 |
| Live positions on the map, hidden while sneaking or invisible, or with `/bmpc hide` | Taran, Q54 |
| The dashboard embeds BlueMap, with the same visibility rules | Peter, Q57 |
| The map and the dashboard's surface are public (season clock, who's online, event feed, Hall, links); leaderboards and full profiles need a login | Peter; Taran, Q58 |
| The dashboard shows who's online and which area they're in | Taran, Q60 |
| Seasons v1 targets Minecraft 26.3 only; TheRanch runs Paper | Peter |
| PolyForm Noncommercial 1.0.0 licence for Seasons, Seasons Web and seasons-api, so nobody else can sell it | Peter |
| The first automated rollover archives Season 2 into a new hallway 2; Season 1's cells are imported | Peter |
| 15 minutes of decorating after archiving, before cells lock | Peter |
| Every kind of bed counts for /hof, straw beds included | Peter |
| Rare items are archived; a curator moving one to the museum is the rare exception | Peter |
| play.petepeterepeat.com with an SRV record; public BlueMap at map.petepeterepeat.com; no extra ports | Peter |
| bStats metrics on; no Bedrock support in v1 | Peter |
| The dashboard is at seasons.petepeterepeat.com | Peter |
| Season 1's hand-built cells convert with /hof import: one archive page per chest, the head's skin on the mannequin, every item checked and undoable | Peter |
| Hall of Fame archives stay view-only forever, even after Homecoming | Peter |
| Development on the MacBook with Homebrew and IntelliJ IDEA Ultimate | Peter |

## Sources

- [The 26.1 Update](https://papermc.io/news/26-1), PaperMC
- [Minecraft 1.21.9 Pre-Release 1](https://www.minecraft.net/en-us/article/minecraft-1-21-9-pre-release-1) (mannequins), minecraft.net
- [Mannequins](https://kore.ayfri.com/docs/helpers/mannequins), Kore docs
- [DimWorldBorder](https://www.mado-hosting.com/minecraft-mods/dimworldborder) (vanilla per-dimension borders since 1.21.9)
- [Minecraft 26.2 "Chaos Cubed"](https://xgamingserver.com/blog/minecraft-26-2-chaos-cubed-release-date-sulfur-caves-everything-new/), XGamingServer
- [Minecraft biome updates 2026](https://minecraft.how/blog/post/minecraft-biome-updates-2026), minecraft.how
- [Minecraft 26.1](https://patchbot.io/games/minecraft/articles/59-minecraft-261), Patchbot
- [MCA Selector chunk filter](https://github.com/Querz/mcaselector/wiki/Chunk-Filter)
- [Map Link](https://modrinth.com/project/maplink), Modrinth
- [Configurate](https://github.com/SpongePowered/Configurate)
- [Paper plugins](https://docs.papermc.io/paper/dev/getting-started/paper-plugins/) and [project setup](https://docs.papermc.io/paper/dev/project-setup/), Paper docs
- [The future of Paper: hard fork](https://papermc.io/news/the-future-of-paper-hard-fork), PaperMC
- [Bukkit / Spigot global stats](https://test.bstats.org/global/bukkit), bStats
- [The 26.2 Update](https://papermc.io/news/26-2), PaperMC
- [Stronghold](https://wiki.sportskeeda.com/minecraft/stronghold) (ring distances), Sportskeeda Minecraft Wiki
- [Plugin config](https://bluemap.bluecolored.de/wiki/configs/Plugin.html) (hide-sneaking, hide-vanished), BlueMap wiki
- [1MB-MapHide](https://github.com/mrfdev/1MB-MapHide) (a fork of BlueMapPlayerControl, /bmpc), GitHub
- [The 26.3 Update](https://papermc.io/news/26-3), PaperMC
- [BlueMap 5.24](https://hangar.papermc.io/Blue/BlueMap/versions/5.24) (26.3 world support), Hangar
- [Reverse proxy BlueMap](https://bluemap.bluecolored.de/wiki/webserver/ReverseProxy.html), BlueMap wiki
- [Origin Rules](https://developers.cloudflare.com/rules/origin-rules/), Cloudflare docs
- [Import and export records](https://developers.cloudflare.com/dns/manage-dns-records/how-to/import-and-export/), Cloudflare docs
