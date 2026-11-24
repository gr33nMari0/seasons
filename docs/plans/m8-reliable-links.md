# M8 Reliable links: plan

Branch `m8-reliable-links`, from `main` at `45ae4dd` (1.3.0-SNAPSHOT: M7 done in #36 and #37, released as 1.2.0 in #38). Approved by the owner on 2026-11-24. Once the owner agrees the plan, slice 1's first commit writes the spec amendments listed at the end into `docs/spec.md`, and the readings into `docs/questions.md` from 317, straight after M7 part 2's 303 to 316 (which took the numbers this plan first claimed). If anything else lands on `main` first, the readings are renumbered after its last entry when this branch rebases.

## What M7 (#36, #37) changes for this plan

M7 touched no Hall code (`core/hall` is as it was before #36), so the design below stands. What it does change:

- **Nothing of M8 goes into Seasons API or the dashboard.** M7 settled that the API never publishes positions: `GalleryView` is "never with their positions", who's online gives an area, never coordinates (questions 298), and the dashboard shows "no coordinates anywhere" beyond shared waypoints (A48). Links, points, landings and past seasons' spawns are all positions, so M8 adds no API type, no `SeasonsEvent` (taking a link is not a feed event: it would say where a player is), and Seasons API stays at 1.3 (#37's version). Seasons Web, the dashboard and the public site need no change.
- **Audit entries reach the dashboard without their details.** Since #37, admins see Seasons' last 50 audit entries by time, actor, action, target and result, with the details left out because they can hold positions (questions 308). So every link and point audit entry M8 writes keeps positions in `details` only: the target is the link or point id (`link:hof-s1`, `point:s1-steps`, `trigger:hof-s1#2`), never `x y z`. `LinkStoreTest#auditTargetsHoldNoPositions` pins it.
- **"Link" now means two things, and more so.** Seasons Web's `web.yml` has `links:` (dashboard URLs, with a `links` visibility key), the first `embed: frame` link is the map (303), a link labelled "rules" shows in the rules widget (309), and players have `/web link <code>`. So Hall links keep their own words everywhere: commands stay under `/hof link` (one more reason not to add a root `/link`), messages under `hof.link.*`, the file is `links.yml` in `<world>/seasons/` (not `plugins/SeasonsWeb/`), and `config.md`, help and the CHANGELOG call them "Hall links" where both could be meant.
- **The dashboard already treats legacy galleries as galleries.** `ApiService#galleries` reads every gallery of a season, legacy ones included, the Hall browser (`/hall/<season>`) shows them, and season history counts a season as held by a gallery when any non-museum gallery has it (`ApiReference#seasons`). Only `/hof <season>` reads `season` galleries alone. The legacy fix below makes the game agree with the dashboard.
- **Past seasons' spawns don't change season history.** History is worked out from player records and galleries (306), not from season files, so `/season spawn record 1` writing `seasons/1.yml` adds no season to it and no dates; A81 checks `/api/v1/seasons` is unchanged.
- **Scenario hooks.** ADR 0003 gained an M7 amendment for Seasons Web's hooks; M8's `/scenario dialog` goes in as a further amendment under the same rule (registered only with `-Dseasons.scenario=true`). No new ADR is needed (the next free number is 0008): dialogs are Paper API and MiniMessage ships with Paper.
- **Config and CHANGELOG.** `config.yml` now has `api.refresh` and `api.afkAfter`; the new `links:` block sits beside them. M8's entries go under `[Unreleased]` with M7's dashboard, connect page and Seasons API 1.3, for 1.3.0, with an *Upgrading* note (see *Upgrading existing setups*).
- **CI.** The shards restore Gradle's download cache, give each shard a web port and now install Chromium and WebKit for the dashboard scenarios; a `site` job is required by the `build` check. M8 adds no browser scenario and doesn't touch `site/`, so neither changes; new scenarios need only their timings in `testkit/scenario-timings.json` so the shards stay balanced.
- **Plans.** `docs/plans/m7-dashboard.md` set the convention this file follows: the plan lives in `docs/plans/`, and `progress.md`'s M8 section points to it and records what turned out different from it.
- **Paper API.** `docs/paper-api.md` now lists M7's calls; M8's additions are appended, not merged into those rows.

## What is wrong today

Read from the code on `main` (`core/hall/HallVisits`, `HallLayout`, `HallFiles`, `Wand`) and questions 248, 253, 260 and 279:

1. **Links fire when nobody clicked them.** `HallVisits#linkUnder` treats every link block as a plate: standing in it, or on top of it, takes the link. A button or sign at foot height, or a block a player jumps onto, sends them. This is spec behaviour (questions 248), so it needs a spec change, not only a fix.
2. **The wand makes links only in the Hall.** Questions 253 refuses wand clicks outside the Hall, so a link at a season's spawn is command-only, and the wand's link has no destination until `/hof link to` ("leads nowhere yet"), which feels broken.
3. **Links are one block or one area, to one place.** No named places, no link-to-link, no two-way pairs, no several triggers for one destination, and a past season's spawn can only be reached through a spot typed by hand.
4. **No way to style link text, no floating text, no command-block use, and every link is open to everyone.**
5. **Changing a box means redrawing it.** Galleries, the hub, cells, exits, entries and link areas can only be reset from two new corners.
6. **`/hof 1` can't find a legacy Season 1 gallery** ("Season 1 has no gallery to visit."), while `/hof gallery list` shows it. Confirmed in the code: `HallVisits#destination` looks the season up through `HallService#seasonGalleries`, which keeps only `kind: season` galleries, the filter that keeps legacy galleries out of assignment, locking and shortfalls (questions 281). It also skips a season gallery with no entry of its own and gives the same message, though `galleryEntry` would fall back to the Hall spawn, so "no gallery" and "no entry" read alike. See *Visiting legacy galleries*, below.
7. **Anchors can't be seen or moved.** The wand's outlines (`HallOutlines`) draw cell bounds, gates and entry anchors, but not figure, archive, plaque or trophy anchors, so an admin can't see where they are without `/hof cell info`. Moving one means setting it again: for the one-per-cell types (figure, plaque, entry) that replaces it, but for archive and trophy anchors (any number per cell) it adds another, and `/hof cell unanchor <cell> <type>` takes off every anchor of that type (`Cell#withoutAnchors`), so moving one archive of three means removing all three and setting them again.

## The model

One link is one destination with any number of triggers. Everything the owner asked for is a combination of these parts.

| Part | What it is | Kept in |
| --- | --- | --- |
| **Link** | `id` (slug), optional display `name`, a destination, a list of triggers, access rules, an optional `landing`, an optional `pair` | `<world>/seasons/links.yml` (new) |
| **Point** | A named spot or box anywhere, with yaw and pitch: `hof-door`, `s1-spawn-steps` | `links.yml` |
| **Destination** | `hall` (Hall spawn), `season <n>` (that season's spawn), `point <name>`, `link <id>` (that link's landing), `gallery <id>` (its entry), `pos <x y z> [yaw pitch]` in a named dimension, `return` (as `/hof leave`) | on the link |
| **Trigger** | How a player takes the link. One of the kinds below. A link may have many | on the link |
| **Landing** | Where a player arrives when another link leads `link <id>` here. Defaults to the free floor in front of the link's first trigger, never inside a trigger | on the link |
| **Access** | Who may take it and when: `everyone`, `op`, or a permission node, plus optional conditions | on the link, defaults in `config.yml` |

**Trigger kinds**

| Kind | Fires when | Typical use |
| --- | --- | --- |
| `use` (default for any block) | Right-click on the block with either hand. Standing on it, jumping on it or walking into it does nothing | Buttons, signs, doors, levers, any decorative block |
| `step` | The game's own "physical" press (pressure plates, tripwire), or, for any other block, a player's feet in the block or on top of it, as links fire today | Pressure plates; a marked floor tile |
| `both` | `use` and `step` together: exactly how every block link behaves today | Kept for upgraded links that relied on standing (see *Upgrading existing setups*) |
| `area` | A player's path crosses the box (walking, falling, flying, elytra), worked out on the move's line as now (279). A 1 × 1 × 1 box is a "trigger block" | Doorways, portals, sky frames |
| `text` | Right-click on a floating text's hitbox; left-click too with `--left`; walking into it too with `--walk <radius>` | Labels in the hub, signposts in the air |
| `command` | `/hof link send <targets> <link>` from a command block, the console or an admin | Redstone contraptions, minigames |

One block holds at most one block trigger (`use`, `step` or `both`), as now. A link with no triggers is allowed (a command-only link).

**Shapes the owner asked for**

- One-to-one: one link, one trigger.
- Many-to-one: one link, many triggers (a sign, a button and a doorway all to Season 2's spawn).
- Two-way: `--two-way` makes the link and its partner `<id>-back`, each leading `link` to the other's landing, and marks them as a `pair`. Renaming or removing one says what happens to the other (removing asks `--both` or unpairs). The partner starts with no trigger; the reply and the editor ask for one ("Now wand-click a block at Season 1's spawn, or `/hof link trigger add s1-back here`").

**No bouncing.** After any of Seasons' moves, every trigger the player stands in counts as already entered (260, widened to all kinds), and `links.arrivalGrace` (default 1 s) holds step and area triggers off. A landing is never inside one of its own link's triggers; `/hof hall check` reports one that is.

## Commands

All under `/hof link` and `/hof point` (existing root, help, tab completion and next steps; `/link` is avoided because DiscordSRV and others commonly use it, and Seasons Web has `/web link`). New node `seasons.admin.links` (op by default) edits links and points anywhere; `seasons.admin.hof` keeps covering every link command, anywhere, as it does today (see Permissions).

Every source and destination takes any of: `x y z` (absolute, `~`, `^`), `here`, `sel` (a box), `looked` (the block looked at within 6), `point <name>`, `link <id>`, and for destinations `hall`, `season <n>`, `gallery <id>`, `return`.

| Command | Does |
| --- | --- |
| `/hof link add <id> [from <source>] to <destination> [--trigger use\|step\|both\|area] [--two-way] [--name <text>]` | Make a link. With no `from`, the block the wand last clicked (pending trigger) or the selection; with neither, a command-only link |
| `/hof link to <id> <destination>` / `from <id> <source> [--trigger …]` | Change where it leads / add a trigger |
| `/hof link trigger add <id> <source> [kind]` / `kind <id> <n> <kind>` / `remove <id> <n\|looked>` / `list <id>` | Manage triggers one by one |
| `/hof link landing <id> [position [yaw pitch]]` / `pair <a> <b>` / `unpair <id>` | Landing, and two-way pairs made after the fact |
| `/hof link rename <id> <new>` / `name <id> <text>` / `remove <id> [--both]` / `info` / `list [near\|world\|to <destination>]` | CRUD; rename rewrites every `link <id>` that points at it; remove is refused while another link leads to it unless `--force` |
| `/hof link access <id> everyone\|op\|permission [node]` / `when <id> <condition>` | Who may take it (see Permissions) |
| `/hof link item <id> [count] [player]` | Give a sign (or `links.item.material`) that becomes a trigger of `<id>` where it is placed |
| `/hof link text add <id> [position] <text>` / `edit` / `scale <id> <n>` / `remove` | Floating text triggers |
| `/hof link go <id>` | Take a link as a player (access applies) |
| `/hof link send <targets> <id\|destination> [--checks none\|entry\|full]` | Send players chosen by a selector (`@p`, `@a[distance=..5]`, a name) along a link. For command blocks |
| `/hof link edit <id>` | Open the editor (below) |
| `/hof link upgrade report` / `done` / `import [--replace]` / `forget-old` | Review and finish the upgrade from 1.1 (see *Upgrading existing setups*) |
| `/hof cell anchors <cell>` / `anchor move <cell> <type> [#n] <position> [yaw]` / `unanchor <cell> <type> [#n]` | List a cell's anchors numbered; move one; remove one of several (see *Moving anchors by dragging*) |
| `/hof point add <name> [position\|box] [yaw pitch]` / `move` / `rename` / `remove` / `list` / `info` / `tp` | Named points; remove is refused while a link uses one |
| `/season spawn record <season> [position] [yaw]` / `clear <season>` | Set or clear a **past** season's spawn (refused for the season in progress and later ones, and inside the Hall; allowed inside an old-world zone, where past spawns are) |

The 1.1 forms keep working unchanged (`add <id> [hall|season <n>] [position | area <box>]`, `to <id> [x y z [yaw]]`, `move`, `info`, `list`, `remove`); see *Upgrading existing setups*. Undo (`/hof undo`) covers `links.yml` as it covers `hall.yml`. Every change is audited.

## In-game tools

**The wand, everywhere.** For a holder of `seasons.admin.links` (or `seasons.admin.hof`), a plain right-click with the wand on any block, in any world, outside a cell opens the link menu (inside a cell, the anchor menu gains a "Link" entry). The menu offers: *Link to the Hall*, *Link to a season's spawn* (one icon per season with a spawn), *Link to a point*, *Link to another link*, *Two-way to…*, *Trigger only on click / on step / as a block area*, *Set as entry / exit / Hall spawn* (inside the Hall), *Use this block later* (pending trigger for a command). Choosing writes the link straight away with the next free id (`l1`, …) and a real destination, so nothing is left leading nowhere. Wand-select then command still works: the pending trigger or selection is what `/hof link add <id> to …` takes. A wand click on a block that is already a trigger opens the editor for its link instead of making a second link there.

**The link editor.** Sneak-right-click on any trigger (with the wand or an empty hand) by a link admin opens the editor instead of taking the link; anyone else sneak-clicking takes it as normal. It shows the link's id, name, source (the trigger clicked, implicit), destination, its other triggers and their kinds, access and pair, with buttons for each change, *Set destination to where I stand*, *Test* (takes it with checks), *Remove this trigger*, *Remove link*. Built with Paper's dialog API (see Paper API, below): text, choice and toggle inputs, every button a server-side callback that re-checks the node and calls the same code as the command. Clients that can't show dialogs (older ones through ViaVersion) get the same choices as a chest menu with typed answers in chat, as curation's reason is typed (questions 215).

**Styling text.** Wherever a trigger has text (a sign or a floating text), the editor's *Text* page has one line input per line, a colour choice per line, toggles for bold, italic, underlined and **glowing** (a sign's glow ink, set without ink; a floating text's full brightness and shadow), a live preview in the dialog body, and accepts MiniMessage for mixed styles inside a line (`<gold>Season <bold>1</bold>`). Picking a colour for part of a line is done with MiniMessage tags in the input, since a dialog can't select a span; the colour buttons insert the tag. Only style tags are accepted (colour, gradient, rainbow, decorations, reset, shadow colour): click, hover, insertion, selector, score and NBT tags are stripped, so sign text can never run a command. The same by command: `/hof link text <id> line <n> <text>`, `/hof link sign <looked> line <n> <text>`, `glow on|off`. Text written on existing link signs by hand is never touched unless an admin edits it.

**Floating text.** A text display with an interaction entity sized to it, both tagged `seasons:link-text`, drawn from `links.yml` and reconciled like other displays (a missing or extra one is put right at chunk load and by `/hof reconcile`). Scale 0.25 to 4: sneak-scroll with the wand while looking at it (as plaques, 278), or `scale`. Billboard (`center`, `vertical`, `fixed` facing the yaw set), background colour, shadow, see-through. It can't be hit, ridden or pushed; left-click only takes the link if `--left` is set.

**Link items.** `/hof link item hall 3` gives three signs named from `links.item` and tagged with the link's id. Placing one (by a holder of `seasons.link.place`, op by default) makes that block a `use` trigger of the link and writes the configured text on it, glowing. Breaking it (builders only, as any link block outside the Hall) removes the trigger and, outside creative, drops the item again. The item carries only an id, so a copy only adds triggers to a link an admin already made.

**Nudging boxes.** With the wand, plain left-click on an outline (the hub, a gallery, a cell, a gate, an entry, an exit, a link area, a point's box, a zone box, or the current selection) loads that box as the selection being edited; the action bar names it and its size. Looking at one of its faces highlights that face (bright plane and a label "+x face"). Then:

- Sneak + scroll up / down moves that face out / in by the current step; plain scroll still changes the slot.
- Drop (Q) with the wand cycles the step through `wand.nudge.steps` (default 1, 10).
- Swap-hands (F) still toggles block / place mode; while editing a box it applies the edit (`wand.nudge.swapHandsApplies`).
- By command: `/hof sel expand <n> [face]`, `contract <n> [face]`, `shift <n> [direction]` (face as `+x`, `-y`, `up`, `down`, `left`, `right`, `forward`, `back`, or `look`), `/hof sel edit <thing>`, `apply`, `cancel`. The editor offers the same as ±1 and ±10 buttons per face.
- Changes are previewed only; `apply` validates the whole box as its own command would (no overlap, the 32-block cap on areas, a cell inside its gallery, the Hall spawn inside the hub) and refuses the whole edit or writes it once, with `/hof undo`. A face can't pass the opposite face. Existing wand clicks keep their meaning: sneak-left and sneak-right still set corners, plain right-click still opens the menu; only plain left-click, which did nothing, is new.

**Seeing anchors.** While an admin holds the wand (or after `/hof show on`), every anchor within `wand.outlines.range` gets a marker, seen only by that admin and drawn the same way as the other outlines (per-player, unsaved, tagged `seasons:outline`, 276):

- A block-sized outline in its type's colour (figure lime, archive gold, plaque light blue, trophy pink, entry purple as now, gate red as now, egg plinth yellow), slightly inset so it shows against a cell's own outline, with a short arrow showing the way it faces.
- A label over it, within `wand.anchors.labelRange` (default 12) so a long hallway isn't a wall of text: `figure`, `archive p2`, `plaque ×1.2`, `trophy 3`, `entry`, `egg S3`.
- A figure anchor is drawn as a 1 × 2 box, the space the figure stands in.
- Looking at an anchor (its marker, its block, or the figure, plaque or trophy display on it) names it on the action bar: "season-3:c4 · archive 2 of 3 · right-click to move".
- The wand cell's line on the action bar lists what it has and lacks: "c4: figure ✓ plaque ✓ archive 2 trophy 0 entry ✗". A `season` cell without a figure gets a red "no figure" label over it, as `/hof hall check` reports.
- Museum anchors (trophies, egg plinths) and legacy galleries' anchors, where any exist, are drawn the same way.

**Moving anchors by dragging.** One wand gesture picks an anchor up, a live ghost follows the crosshair, and a second click puts it down:

- **Pick up.** Plain right-click with the wand on an anchor (its marker, its block, or the display on it; the click that a visitor's interaction with a display gets cancelled for is the admin's, not the archive view's). The anchor is now held: its marker turns white and dashed, and the action bar says "Holding season-3:c4 archive 2 · right-click to place · sneak-scroll to turn · left-click to cancel".
- **Live preview.** While held, the block the anchor would go to follows the admin's crosshair (worked out every 2 ticks, within 8 blocks, by 227's rule: the block looked at for an archive or plaque, the block in front of the face looked at for a figure, entry, trophy or gate corner). It is drawn as a ghost: the type's outline and label in place, a 1 × 2 box for a figure, the whole new plane for a gate corner. Green where it may go; red, with the reason on the action bar, where it may not ("outside c4's bounds", "already an archive here", "the gate plane would cross c5").
- **Turn.** Sneak + scroll turns the held anchor's facing by 45° (the arrow turns with it). This takes precedence over plaque sizing and face nudging only while something is held.
- **Put down.** Right-click: the anchor moves there, through the same code and checks as `/hof cell anchor`, as one edit with one `/hof undo` step, audited (target `anchor:season-3:c4:archive#2`, positions in the details only). Its page, egg season, scale and box size come with it. The display on it (figure, plaque text, trophy items) is redrawn at the new place at once; nothing else in the cell changes.
- **Cancel.** Left-click, swapping to another item, logging out, the cell's file being changed by someone else, or 60 s without a move (`wand.anchors.holdTimeout`) puts it back untouched.
- **Where it may go.** Anywhere its own rules allow inside its own cell (a gate corner also in the layer just outside, 250). Dragging an anchor into another cell isn't a move: it is refused with "use the wand menu there to add one", so a misplaced drag can't empty a cell. An anchor of a cell someone has been archived into asks for a second right-click to confirm, as `--force` does for `/hof cell move` (A69).
- **The block under it.** An archive or plaque anchor is a block the builder placed (a chest, a lectern). Moving the anchor leaves that block where it is, as decoration; swap-hands while holding toggles "carry the block too", which moves the block with it when the block is empty and the target is air (journalled before the two block changes, refused for anything holding items).
- **The same gesture for other single spots.** Link block triggers and landings, named points, the Hall spawn, gallery entries and exit blocks are picked up and put down the same way (each through its own command's checks); boxes are moved whole the same way and resized by face nudging.
- **By command, for the same result:** `/hof cell anchors <cell>` lists a cell's anchors numbered, each with clicks to go there or pick it up; `/hof cell anchor move <cell> <type> [#n] <position> [yaw]` moves one; `/hof cell unanchor <cell> <type> [#n]` now takes `#n` to remove one of several (without it, all of that type, as now).

**Entries, exits and the Hall spawn as blocks or boxes.** Already allowed by 279; M8 adds the wand menu's *Set as gallery entry / cell entry / Hall spawn / exit* on a clicked block or on the selection, labels on each (`entry season-2`, `exit`, `Hall spawn`), outlines for single-block ones too, and new exits can take trigger kinds: an exit made on a plate or floor block is `step` (as today), one made on a button or sign is `use`. Existing exits stay exactly as they are.

## Visiting legacy galleries

The rule that keeps legacy galleries out of anything automatic should not keep visitors out. Fix:

- **A visitable gallery** is one of kind `season` or `legacy` (not `museum`). `HallService` gains `visitableGalleries(season)`; `seasonGalleries` keeps its meaning for assignment, locking, shortfalls, free cells and the draw, so none of 281's rules move.
- **`/hof <season>`** picks, among that season's visitable galleries, the first with its own entry (`season` before `legacy`, then by id). If the season has galleries but none has an entry, it says so plainly ("Season 1's gallery has no entry yet.") and an admin also gets the clickable next step `/hof gallery entry <id>`; it no longer lands at the Hall spawn or claims there is no gallery. Only a season with no visitable gallery says "Season 1 has no gallery to visit." For a server with only `season` galleries that all have entries, nothing changes.
- **Tab completion** for `/hof <season>` offers every season with a visitable gallery, legacy ones included.
- **`/hof tp gallery`, M8's `gallery <id>` destination and link landings** take legacy galleries as they do any other (`galleryEntry` never filtered by kind; a test pins it).
- **Left as they are:** `/hof <player>`, `/hof locate <player>` and `/hof gate` still look only in `season` galleries, since legacy cells never have owners (281). `/hof hall check` already reports a legacy gallery with no entry (255); its line gains "so /hof 1 can't land there".
- **Until it ships:** admins use `/hof tp gallery <id>`; for players, a link to a spot in the gallery (`/hof link add s1 here`, then `/hof link to s1` standing in the legacy wing).

## Permissions

All plain Bukkit permission nodes, registered with the server (`PluginManager#addPermission`) so LuckPerms lists and completes them; no LuckPerms API and no new dependency.

| Node | Default | Covers |
| --- | --- | --- |
| `seasons.admin.links` | op | Make, change and remove links and points anywhere; the editor; `send`. A child of `seasons.admin.hof`, so anyone who holds `seasons.admin.hof` today keeps every link command they have now |
| `seasons.link.place` | op | Placing link items |
| `seasons.link.use.<id>` | per link (below) | Taking a link whose access is `permission` with no node of its own |
| `seasons.link.use.*` | false | Every permission-gated link (LuckPerms wildcard) |
| `seasons.link.text.style` | op | Styling sign and floating text through Seasons |

Access per link: `everyone`, `op`, or `permission [node]`. Defaults by destination kind in `config.yml` for **new** links: links to the Hall and to season spawns are `everyone`; every other link is `op` until an admin sets it. Links that exist before the upgrade are written with `access: everyone`, as they work today. Conditions (`when`): `always` (default), `after-homecoming`, `stage <frontier|open>`, `played <season>` (the player has a record in that season). A refusal says why without naming the node ("You can't use this link."), and is audited at debug level only.

These sit on top of the rules that already hold and are not loosened: locked zones refuse links into them before Homecoming unless the player holds `seasons.zone.bypass`, links out of the Hall are refused during the Intermission except for `seasons.hof.roam`, and the rate limit applies (`links.rateLimit`, default `/hof`'s 5 s).

## Command blocks

`/hof link send <targets> <link|destination>` accepts vanilla selectors through Paper's player selector argument, from command blocks (when `links.commandBlocks.enabled`, default true), the console and link admins. Only players are moved; other entities a selector picks are skipped and counted in the reply (`links.commandBlocks.playersOnly: true`; the Hall's protection doesn't want mobs carried in). `--checks` sets which checks apply (default from `links.commandBlocks.checks: entry`: the link's access, the zone lock, the Intermission rule, then the entry checks but the bed; `none` keeps only the zone lock and the Intermission rule; `full` adds the bed). Players use `/hof link go <id>` themselves; it never skips access.

## Upgrading existing setups

Servers already have links (TheRanch: hub links to each season's spawn, a link back at each spawn, plates and doorway areas), exits, scripts and command blocks calling `/hof link`, LuckPerms groups holding `seasons.admin.hof`, and players mid-visit. The rule for M8: **an existing setup keeps working as it does today after the upgrade, apart from the one change asked for (clickable blocks no longer fire when stood on), and going back to the previous jar still works.** Everything else is opt-in.

**1. Files: copied, never moved, and no schema bump.**

- On the first start of the new version, if `links.yml` doesn't exist, every link in `hall.yml` is copied into a new `links.yml` (written atomically: temp file then rename, so a crash leaves either no file, and the copy runs again, or a whole one). `hall.yml` is backed up but **not changed**: its `links:` stay in it as a frozen copy, which the new version never reads once `links.yml` exists, and which it carries through unchanged whenever it writes `hall.yml` (hub, Hall spawn, exits).
- No file's `schema-version` changes in M8 (`ConfigLoader` refuses a file newer than it reads, so a bump would stop the old jar loading). `links.yml` is a new file the old jar doesn't know about.
- So **going back to 1.1 or 1.2 is swapping the jar**: the old jar reads `hall.yml` and its frozen links, as they were at the upgrade. Links made or changed since then live only in `links.yml` and come back on the next upgrade.
- If `links.yml` exists and `hall.yml`'s frozen links differ from what was copied (an admin went back to the old jar and changed links there), the new version keeps `links.yml`, and `/hof hall check` and the startup summary say so; `/hof link upgrade import --replace` copies them again (with a backup of `links.yml` and one `/hof undo`). Once the owner is sure they won't go back, `/hof link upgrade forget-old` removes the frozen copy from `hall.yml` (backed up).

**2. Each link's behaviour, kept or deliberately changed.**

| Today in `hall.yml` | After the upgrade in `links.yml` |
| --- | --- |
| id, dimension, block, area, destination | Kept as they are: same id, same dimension. `to: hall` → `hall`; `to: { season: n }` → `season n`; `to: { pos, yaw }` → `pos` with the same yaw; no `to` → no destination ("leads nowhere yet", as now) |
| A link on a pressure plate or tripwire | `step` (fires on the press, as now) |
| A link on a block whose right-click does something: buttons, signs and hanging signs, levers, doors, trapdoors, fence gates, bells, lecterns, note blocks, and any other block the server reports as interactable | `use`: click only. **The one deliberate change**, the fix the owner asked for |
| A link on any other block (stone, carpet, slab, a floor tile) | `both`: click or stand on it, exactly as now, since such a block can only have been meant to be stood on or clicked |
| A link with an area | The same `area` trigger, firing as now |
| Who may take it | `access: everyone`, as now (the new `op` default for links to points and spots applies only to links made after the upgrade) |
| `hof.links.returnToLink` in `config.yml` | Still read. `links.returnToLink` is read first when set; with both set, a load warning names the one in force. `/season config set hof.links.returnToLink` keeps working. `config.yml` is not rewritten |

`links.upgrade.keepStepping` chooses the third row: `non-interactive` (the default, as above), `plates-only` (every non-plate block becomes `use`), or `all` (every block link keeps `both`, so nothing changes at all until an admin changes it link by link). It is read only when the copy is made.

**3. Saying what changed, and undoing it per link.**

- The startup summary gains one line: "Links upgraded: 9 copied to links.yml; 4 are now click-only (buttons and signs); hall.yml kept as it was." Each admin joining is told once, with a click to `/hof link upgrade report`, until one of them runs `/hof link upgrade done`.
- `/hof link upgrade report` lists every copied link: its id, where it is, its destination, and its trigger before and after, with clicks to put any one back (`/hof link trigger kind <id> 1 both`) or make it click-only (`… use`). It is audited as `links-upgraded` (targets are ids, no positions).
- `/hof hall check` notes a `pos` link within 8 blocks of a season spawn recorded since (with `/season spawn record`) and offers the click `/hof link to <id> season <n>`; nothing is switched by itself.

**4. Commands, scripts and command blocks.**

- The 1.1 grammar keeps working, alongside the new one, until at least 2.0: `/hof link add <id> [hall|season <n>] [position | area <box>]`, `/hof link to <id> [x y z [yaw]]` (with nothing, where the admin stands), `/hof link move <id> [position | area <box>]` (refused, with the new commands suggested, only for a link that has more than one trigger, which the old grammar couldn't make), `info`, `list`, `remove`, `/hof tp link`, `/hof locate link`. Their replies stay word for word, so anything that reads them, and the existing scenarios, are unaffected; `help` marks them as the older form and shows the new one.
- A block link made with the old grammar gets the trigger the upgrade would give it (table above), so a script that makes a link on a stone tile and expects it to fire underfoot still gets that. The new grammar and the wand use the new defaults.
- Message keys under `hof.link.*` keep their names and placeholders; new text gets new keys, so a server's own `messages.yml` keeps its wording and gains the new keys' defaults as it does today.

**5. Permissions, players and the world.**

- `seasons.admin.links` is a child of `seasons.admin.hof`, so groups that manage the Hall today manage links as before, anywhere (links outside the Hall were already theirs, by command); the wand menu outside the Hall opens for them too.
- A player's saved spot for `/hof leave` is a position, not a link id (`HallReturn`), so anyone in the Hall across the restart returns as before; `returnToLink` keeps sending them back to the link they came by.
- Nothing in the world is changed by the upgrade: link blocks, sign text and exits stay as built, and link blocks outside the Hall stay protected as before.
- Exits stay in `hall.yml`, unchanged, firing as they do today; trigger kinds for exits apply only to exits made after the upgrade.
- Anchors keep their format in the gallery files; markers and dragging read and write them as `/hof cell anchor` does, and `unanchor <cell> <type>` without `#n` still removes every anchor of that type.

**6. Before and after, for an admin (runbook and CHANGELOG).** Back up the world folder; swap the jar; read the startup line and `/hof link upgrade report`; walk the hub and each season's spawn once, clicking each link and standing on each plate; put back `both` on any link players are meant to walk onto; `/hof link upgrade done`. To go back: stop, put the old jar back, start.

## Configuration (new keys, defaults chosen as placeholders, all editable)

```yaml
links:
  returnToLink: true             # hof.links.returnToLink is still read when this isn't set
  arrivalGrace: 1s
  rateLimit: 5s
  defaultTrigger: { block: use, pressurePlate: step, tripwire: step, exit: step }
  access: { hall: everyone, seasonSpawn: everyone, other: op }   # new links only
  upgrade: { keepStepping: non-interactive }    # non-interactive, plates-only or all; read once, at the copy
  editor: { sneakClick: true, ui: dialog }      # dialog or menu
  item:
    material: minecraft:oak_sign
    name: "<gold>Hall of Fame sign"
    lines: ["", "<bold><gold>Hall of Fame", "<gray>right-click", ""]
    glowing: true
  text: { scale: 1.0, billboard: center, background: "#40000000", shadow: true, fullBright: true }
  commandBlocks: { enabled: true, checks: entry, playersOnly: true }
  styleTags: [color, gradient, rainbow, decorations, reset, shadow_color]
wand:
  nudge: { steps: [1, 10], swapHandsApplies: true }
  anchors: { markers: true, labelRange: 12, holdTimeout: 60s, carryBlocks: true }
```

Every key is optional: a `config.yml` from 1.1 without a `links:` block loads with these defaults and behaves as described under *Upgrading existing setups*.

`links.yml` (command-editable, `schema-version: 1`):

```yaml
schema-version: 1
upgraded: { from: hall.yml, at: 2026-11-24T19:00:00+11:00, done: false }
points:
  s1-steps: { dimension: minecraft:overworld, pos: [512, 70, -300], yaw: 90 }
links:
  hof-s1:
    name: "<gold>Season 1"
    to: { season: 1 }
    access: everyone
    pair: hof-s1-back
    triggers:
      - { use: { dimension: minecraft:overworld, pos: [3, 257, -8] } }
      - { area: { dimension: minecraft:overworld, min: [10, 256, -2], max: [10, 259, 2] } }
      - { text: { dimension: minecraft:overworld, pos: [0.5, 258.2, -6.5], lines: ["<bold>Season 1"], scale: 1.5, billboard: center } }
  hof-s1-back:
    to: { link: hof-s1 }
    pair: hof-s1
    triggers:
      - { use: { dimension: minecraft:overworld, pos: [512, 71, -296] }, text: ["", "<gold>Hall of Fame", "", ""], glowing: true }
  floor-tile:                       # copied from hall.yml: a stone tile, so it keeps firing underfoot
    to: hall
    access: everyone
    triggers:
      - { both: { dimension: minecraft:overworld, pos: [0, 255, 9] } }
```

Past seasons' spawns go in their own `seasons/<n>.yml` (`spawn:` only), as a recorded one does.

## Paper API (checked against the cached `paper-api-26.3.build.257-stable` jar)

- **Dialogs** (`io.papermc.paper.dialog.Dialog`, `registry.data.dialog.*`: `DialogBase`, `ActionButton`, `DialogAction.customClick`, inputs for text, single option, boolean and number range; `Audience#showDialog`): no longer `@ApiStatus.Experimental` in this build, so question 215's reason for avoiding them no longer applies. Recorded as a new reading; the chest-menu fallback stays for clients that can't show them.
- **Selectors**: `ArgumentTypes.players()` and `entities()` with their resolvers are stable (the experimental ones are column positions, angle, axes, block predicate and resource keys, none needed).
- **The upgrade's block test**: whether a block is interactable, from the block's type (`BlockType#isInteractable`, or `Material#isInteractable` if that is the one not deprecated in 26.3; checked when slice 1 starts), plus Paper's `Tag.PRESSURE_PLATES` and tripwire.
- Also new: `Sign#getSide`, `SignSide#line(int, Component)`, `#setGlowingText`, `#setColor`, `Sign#setWaxed`; `TextDisplay` billboard, background, shadow, see-through, brightness, line width; `Interaction` width, height and responsiveness; `PlayerInteractEntityEvent` on interactions and `PrePlayerAttackEntityEvent` for left-click; `PlayerDropItemEvent` (step cycling); `Player#getTargetBlockExact` and `#getTargetBlockFace` (the drag's preview and the face looked at), `PlayerItemHeldEvent` (turning while holding, as plaque scrolling does); `BlockPlaceEvent` with the item's persistent data; `BlockCommandSender`; `PluginManager#addPermission`, `Permission` (with children), `PermissionDefault`; MiniMessage (bundled in Paper, so not a new dependency). Each goes in `docs/paper-api.md` as it's used.

## Acceptance scenarios

New scenarios A77 to A95, plus widened ones. Each end-to-end test is a Mineflayer scenario in `testkit/bots/scenarios`, written first and failing before its code, with the unit tests named. The dialog itself can't be driven by Mineflayer (and the scenario servers run ViaVersion), so the test plugin gains `/scenario dialog <player> <button> [input=value…]`, which calls the same callback a client's click would, and the dialog's contents are unit tested. Every scenario that exists on `main` today passes unchanged unless listed as widened below.

| Scenario | Test |
| --- | --- |
| **A77 Click-only triggers.** A sign, a button, a door and a lever made links with the new grammar are taken only by a right-click: a bot standing on each, on the block above, jumping onto it and walking into it goes nowhere; the button gives no redstone. A pressure plate link (`step`) fires on stepping, once until stepped off; a 1 × 1 × 1 trigger block fires on walking into it; a `both` floor tile fires on click and on standing. | `a77-click-only-triggers.mjs` (fails on `main` for the standing cases); unit: `LinkTriggersTest#useTriggersIgnoreStandingAndJumping`, `#stepFiresOncePerPress`, `#bothIsTodaysBehaviour`, `#aTriggerBlockIsAOneBlockArea` |
| **A78 The wand anywhere.** In the overworld 2,000 blocks from the Hall, and in the Nether, an admin's wand click on a button opens the link menu; *Link to the Hall* makes it a working link at once; at another block *Use this block later* then `/hof link add s3-back to hall` confirms it; a wand click on an existing trigger opens its editor; a holder of only `seasons.admin.hof` gets the same menu; a player without either node holding a wand gets nothing and the block isn't used. | `a78-wand-anywhere.mjs`; unit: `WandTest#outsideTheHallTheMenuOffersLinks`, `#aPendingTriggerIsTakenByLinkAdd`, `#aTriggerOpensItsEditor` |
| **A79 Any source, any destination.** Links from `x y z`, `~`, `here`, `looked`, `sel` and `point` to `pos`, `here`, `point`, `link`, `season 3`, `hall`, `gallery season-2` and `return` each land where they should, facing the yaw set; `link` lands at that link's landing, not on its trigger; a destination in another dimension works. | `a79-link-combinations.mjs` (one per destination kind, sources rotated through); unit: `LinkSpecTest` (parameterised over every source and destination word, including refusals: unknown point, a link leading to itself, a box where a spot is needed) |
| **A80 One-to-one, many-to-one, two-way.** A sign, a button, a doorway area and a floating text on one link all reach the same spot; `--two-way` makes `hof-s3` and `hof-s3-back`, the back link asks for a trigger, each lands at the other's landing, and arriving never fires the trigger landed in; removing one refuses without `--both`, and `unpair` keeps both. | `a80-link-shapes.mjs`; unit: `LinkStoreTest#twoWayMakesAPair`, `#landingsAreNeverInsideTheirOwnTriggers`, `HallVisitsTest#arrivingCountsEveryTriggerAsEntered` |
| **A81 Past seasons' spawns.** `/season spawn record 1 here 90` inside the old-world circle and `record 2 400 70 -900` are kept; `record 3` (the season in progress), `record 5`, and one inside the Hall are refused; `spawn show 1` shows it; a two-way link from the hub to Season 1's spawn is refused before Homecoming with the reason and works after it; its back link at Season 1's spawn takes a player to the hub's link landing with no bed; `/api/v1/seasons` lists the same seasons as before the spawns were recorded; `/hof hall check` offers to switch a `pos` link beside Season 1's new spawn to `season 1`. | `a81-past-season-spawns.mjs`; unit: `SpawnRecordTest#onlyPastSeasonsAndOutsideTheHall`, `LinkAccessTest#seasonSpawnLinksStillHonourTheZoneLock`, `HallCheckTest#aSpotLinkBesideARecordedSpawnIsNoted` |
| **A82 Link items.** `/hof link item hall 3` gives three named signs; placing one at Season 3's spawn makes a glowing sign reading the configured lines and a trigger of `hall`; a bot right-clicking it arrives at the Hall spawn; a survival player can't break it; a builder breaking it removes the trigger and gets the item back; a player without `seasons.link.place` can't place one. | `a82-link-items.mjs`; unit: `LinkItemsTest#placingAddsATriggerAndBreakingRemovesIt` |
| **A83 Points and CRUD.** `/hof point add|move|rename|remove|list|info|tp`; `/hof link rename` rewrites a link leading `link <old>`; removing a point or link still used is refused, `--force` leaves the users leading nowhere and lists them; `info` shows triggers, destination, access, pair and landing; `list to season 1` and `list near`; `/hof undo` puts back the last links edit; every change audited, with no position in an entry's target (the dashboard's audit widget shows targets). | `a83-links-crud.mjs`; unit: `LinkStoreTest#renameRewritesReferences`, `#removeIsRefusedWhileUsed`, `#auditTargetsHoldNoPositions`, `HallUndoTest#linksYmlIsUndoneLikeHallYml` |
| **A84 The link editor.** An admin sneak-right-clicking a trigger gets the editor (the bot sees the dialog packet) and takes nothing; a non-admin sneak-clicking it takes the link; through `/scenario dialog`, the admin changes the destination to `season 3`, changes a trigger's kind, adds the block as a second trigger of another link, sets access to `op`, and each change is in `links.yml` and audited; a callback replayed after the admin loses the node is refused. | `a84-link-editor.mjs`; unit: `LinkEditorTest` (the form for each link shape, every button mapped to its command, the node re-checked on every callback), `PaperDialogsTest` (the dialog built from the form) |
| **A85 Styling text.** Through the editor and by command, an admin sets a sign trigger's lines to gold bold, a line mixing colours with MiniMessage, and glowing on, without ink; the bot reads the sign's text components and the glow flag; a `<click:run_command>` tag is stripped; a player without `seasons.link.text.style` is refused; the floating text takes the same styles; a hand-written link sign nobody edits keeps its text through a restart and reconciliation. | `a85-link-text-style.mjs`; unit: `LinkTextTest#onlyStyleTagsSurvive`, `#linesAreClampedToTheSignsWidth` |
| **A86 Floating text links.** `/hof link text add hof-s3 ~ ~2 ~ "<gold>Season 3"` shows a text a bot can right-click to take the link; walking through it does nothing until `--walk 1`; left-click does nothing until `--left`; sneak-scroll with the wand and `scale` resize it and its hitbox; it can't be hit or pushed; after a restart and `/hof reconcile` exactly one text and one hitbox remain; it shows in the admin's outlines. | `a86-floating-text-links.mjs` (two sessions); unit: `LinkTextTest#hitboxFollowsScaleAndLineCount`, `ReconcileTest#linkTextsAreRedrawnOnce` |
| **A87 Command blocks.** A command block running `hof link send @p hof-s3` behind a button sends the nearest bot; `@a[distance=..5]` sends the two within 5 and not the third; `@e[type=minecraft:pig]` moves no pig and says so; `--checks none` sends a bot that just took damage, the default refuses it; a link into the locked old world is refused for every target before Homecoming; a player without the node can't run `send`, and `/hof link go` honours the link's access. | `a87-command-block-links.mjs`; unit: `LinkSendTest#checksPerLevel`, `#onlyPlayersAreMoved` |
| **A88 Permissions and conditions.** A Hall link and a season-spawn link work for a plain player; a new link to a point is op-only by default; `access permission` lets a player granted `seasons.link.use.<id>` (or `seasons.link.use.*`) through and refuses one without; a custom node works the same; `when after-homecoming` refuses before Homecoming and allows after; the nodes are registered with the server, `seasons.admin.links` as a child of `seasons.admin.hof`. | `a88-link-permissions.mjs` (grants with `/scenario permit`, as LuckPerms would through Bukkit); unit: `LinkAccessTest` |
| **A89 Nudging any box.** An admin left-clicks a gallery's outline with the wand, looks at its +x face (highlighted and named), sneak-scrolls up three times (+3), presses Q (step 10) and scrolls once (+10), scrolls down once (−10); `/hof sel apply` writes +3 with one undo step; growing it into the hub is refused whole; the same works for the hub, a cell, an exit, a gallery entry, a link area and a zone box; `/hof sel expand 2 up`, `contract 1 -x` and `shift 4 north` by command; sneak-left and sneak-right still set corners. | `a89-nudge-faces.mjs`; `a67-wand-place.mjs` passes as before; unit: `SelectionFacesTest#theFaceLookedAtIsPicked`, `#aFaceCantCrossItsOpposite`, `#areasStayWithin32`, `#applyValidatesLikeTheOwningCommand` |
| **A90 Entries and exits by wand.** A gallery entry, a cell entry and the Hall spawn set as a single block and as a box from the wand menu; a new hub exit as a single plate (`step`), as a button (`use`) and as a doorway box; each drawn with its label for the admin and none for a visitor; the new exit button isn't taken by standing on it; an exit set before the upgrade fires as it did. | `a90-entries-exits-wand.mjs`; unit: `OutlinesTest#entriesExitsAndTriggersAreLabelled` |
| **A91 Upgrading an existing setup.** A world folder written by 1.1.0 (a fixture like TheRanch's hub: a sign link and a button link to season spawns, a plate link, a stone floor-tile link, a doorway area link, a `pos` link with a yaw, a link leading nowhere, and the link back at Season 3's spawn) starts on the new jar: `links.yml` holds every link with the same id, dimension, destination and yaw and `access: everyone`; the sign and button fire only on click, the plate and the floor tile fire underfoot as before, the area as before; `hall.yml` is byte for byte as it was; the startup line and the admin's join message report the counts; `/hof link upgrade report` lists each with before and after, a click there puts the button back to `both`, and `done` stops the reminder; a visitor in the Hall across the restart `/hof leave`s back to where they were; with `keepStepping: all` every block link keeps firing underfoot. | `a91-links-upgrade.mjs` (fixture world in `testkit`); unit: `LinksMigrationTest#eachLinkKeepsItsIdDestinationAndAccess`, `#triggerKindsByBlock`, `#keepSteppingSettings`, `#theCopyIsAtomicAndRunsOnce` |
| **A92 Visiting a legacy gallery.** With a legacy Season 1 gallery with an entry, and a `season` Season 2 gallery, `/hof 1` lands at Season 1's entry and `/hof 2` at Season 2's; tab completion offers 1 and 2; with the legacy entry removed, `/hof 1` says the gallery has no entry yet (an op also gets the `/hof gallery entry` step) and lands nowhere; `/hof 7` says Season 7 has no gallery; `/hof tp gallery season-1` and a link to `gallery season-1` land there; the legacy gallery is still never assigned, locked or counted in the dry run. | `a92-visit-legacy-gallery.mjs` (fails on `main` at `/hof 1`); unit: `HallVisitsTest#aSeasonsLegacyGalleryIsVisitable`, `#aGalleryWithoutAnEntrySaysSo`, `HubTest#legacyGalleriesStayOutOfTheDraw`; `a74-legacy-gallery.mjs` passes as before |
| **A93 Going back, and the old commands.** After the upgrade, an admin makes a new link; the server is stopped and started on the previous jar: it loads without a problem, every link from before the upgrade works as it did on that jar, and the new link is absent; back on the new jar, `links.yml` (with the new link) is in force and `hall.yml` unchanged; an old-jar edit to a link is reported by `/hof hall check`, and `upgrade import --replace` takes it (undone by `/hof undo`); `upgrade forget-old` clears the frozen copy with a backup. The 1.1 forms `/hof link add s9 season 3`, `add tile hall ~ ~-1 ~` on stone, `add door hall area ~ ~ ~ ~ ~2 ~2`, `to s9 10 64 10 90` and `move` reply word for word as on 1.1, and `tile` fires underfoot. A holder of only `seasons.admin.hof` runs every link command. | `a93-links-downgrade.mjs` (`@isolated`: two jars, the previous release from the Gradle cache, as A76's restarts run); `a54-links.mjs`, `a65-crud.mjs`, `a72-areas.mjs` pass unchanged; unit: `HallFilesTest#writesCarryTheFrozenLinksThrough`, `#noSchemaVersionChanges`, `LinkCommandsTest#oldFormsReplyAsBefore`, `#oldFormsGiveTheUpgradesTriggers` |
| **A94 Seeing anchors.** An admin holding the wand in a stamped gallery sees a marker and label for each cell's figure, archive (two, labelled with their pages), plaque (with its scale), trophy and entry, and the museum's egg plinth, each in its colour with its facing arrow; labels only within 12 blocks; looking at the figure names it on the action bar; the wand cell's line lists what it has and lacks, and a cell with no figure shows a red label; a visitor sees none of it; putting the wand away, `/hof show off`, logging out and a restart leave no marker behind. | `a94-anchor-markers.mjs` (two sessions; the bots count the display entities they are sent, as `a68` does); unit: `OutlinesTest#everyAnchorHasAMarkerAndLabel`, `#labelsOnlyNearby`, `#theWandCellsLineListsItsAnchors` |
| **A95 Dragging anchors.** An admin right-clicks a cell's figure with the wand, looks across the cell (the ghost follows, green), sneak-scrolls twice (facing +90°), and right-clicks the floor: the figure stands there facing the new way, and one `/hof undo` puts it back. The second of three archive anchors is dragged to another chest: the other two stay, its page comes with it, and the first chest is left as it was; with "carry the block too" an empty barrel moves with its anchor, and a chest with items in it isn't carried. A plaque keeps its scale. A drag outside the cell, into another cell, or a gate corner whose plane would cross a neighbour shows red and isn't placed; left-click cancels; 60 s without a move cancels. An archived-into cell asks for a second click. Another admin's edit to the gallery while holding cancels the hold. A link trigger and the Hall spawn move the same way. `/hof cell anchor move c4 archive #2 ~ ~ ~1` and `unanchor c4 archive #3` do the same by command. | `a95-anchor-drag.mjs` (two sessions); `a69-cell-fixes.mjs` passes as before; unit: `AnchorDragTest#theGhostFollows227sRule`, `#placingIsOneEditWithOneUndo`, `#onlyTheHeldAnchorOfSeveralMoves`, `#refusedOutsideItsCellAndIntoAnother`, `#carryingOnlyEmptyBlocks`, `#aConcurrentEditCancelsTheHold` |
| **A54 widened** | `a54-links.mjs` passes unchanged: its links are made with the 1.1 forms, so they keep 1.1's triggers |
| **A58 widened**: `/hof hall check` reports a link leading to a missing point, link or season spawn, a two-way partner with no trigger, a landing inside its own trigger, a floating text in an unloaded world, and frozen links in `hall.yml` that differ from `links.yml` | `a58-hall-check.mjs`; unit: `HubTest#theCheckReportsWhatDoesntFitTogether` |
| **A63, A64 widened**: completion and help for every new command, points, link ids, trigger kinds, destination words and selectors; the 1.1 forms shown as the older form | `a63-tab-complete.mjs`, `a64-help.mjs`; unit: `HelpTest` |
| **A66 widened**: `/hof tp link <id>` lands at its landing; `/hof point tp` | `a66-admin-tp.mjs` |
| **A68 widened**: outlines for triggers (use, step and both blocks, areas, text hitboxes), landings, points and the highlighted face, admin only | `a68-outlines.mjs`; unit: `OutlinesTest` |
| **A43 widened**: with 200 links and 600 triggers over three worlds, Seasons' average tick stays under 1 ms (triggers looked up by chunk, not scanned) | `a43-performance.mjs`; unit: `TriggerIndexTest` |

## Slices

Each slice ends with its scenarios and unit tests passing locally (only the touched scenarios, the full suite once in the background before pushing), in small conventional commits. Every slice also runs the existing link scenarios (`a54`, `a61`, `a63`, `a65`, `a66`, `a68`, `a72`) so nothing an existing setup relies on regresses on the way.

0. **Red first, and the legacy fix.** `a77` and `a78`'s failing halves, reproducing the walk-over firing and the wand refusal outside the Hall; `a91`'s fixture world, captured from a 1.1.0 server; then A92 red, fixed and green. The legacy fix is small and independent of the rest, so it can go out as its own pull request (and a CHANGELOG line) ahead of M8 if TheRanch needs `/hof 1` sooner.
1. **Docs, model and the upgrade.** Spec amendments and readings 317 onwards; `links/` package in core: `Link`, `Trigger`, `Destination`, `Point`, `LinkStore`, `LinksFiles` (Configurate, backups, undo), the copy from `hall.yml` with its frozen links carried through `HallFiles`, the 1.1 command forms over the new model, `links.returnToLink` with its fallback, `/hof link upgrade …`, `/season spawn record`. A79, A81, A83, A91, A93.
2. **Triggers.** `TriggerIndex` by dimension and chunk; use, step, both and area semantics; arrival grace; new exits on the same triggers, existing exits as they are. A77, A80, A54, A43.
3. **Wand and outlines.** Wand menu anywhere, pending trigger, set-as entry/exit/spawn, labels. A78, A90, A68.
4. **Access.** Per-link access and conditions, registered nodes with `seasons.admin.links` under `seasons.admin.hof`. A88.
5. **Send and go.** Selectors, command blocks, check levels. A87.
6. **Link items and sign text.** A82, A85's command half.
7. **Floating text.** Text and interaction entities, reconciliation, scaling. A86.
8. **Editor.** `Forms` port in core (neutral form model), `PaperDialogs` and the chest-menu fallback, `/scenario dialog`. A84, A85.
9. **Nudging, anchor markers and dragging.** Face picking by ray in core, step cycling, `sel edit|expand|contract|shift|apply|cancel`, validation through each owner's command; anchor markers and labels in `HallOutlines`; `AnchorDrag` in core (held anchor per admin, preview rule, checks, timeout) with the Paper side's ray trace and preview displays; `cell anchors`, `anchor move`, `unanchor #n`. A89, A94, A95.
10. **Finish.** `config.md`, `paper-api.md`, ADR 0003's M8 amendment, CHANGELOG under `[Unreleased]` with an *Upgrading from 1.2* note (what changes for players, how to review, how to go back), README's feature list, progress (an M8 section pointing here, with what turned out different), the runbook's link and upgrade steps, scenario timings; widened scenarios; full suite; pull request into `main`.

## Spec amendments this needs (for the owner's agreement)

- **Links (Visiting the Hall of Fame).** A link is a destination with triggers of six kinds; blocks are click-only unless marked `step` or `both`; pressure plates and tripwire are `step` by default. Replaces "stepping on it if it is a plate" and questions 248's "or standing on it (or on the block above it)" for links made from now on; links that exist at the upgrade keep stepping where their block can only have been meant to be stood on (*Upgrading existing setups*).
- **Wand.** The link menu works anywhere for link admins, replacing 253's "links outside the Hall are made by command".
- **Data files.** `links.yml` holds links and points; `hall.yml` keeps the hub, the Hall spawn and exits, and keeps its 1.1 `links:` as a frozen copy for going back until an admin forgets it.
- **Configuration and data files.** A milestone that changes a file's layout adds a file rather than bumping a schema where it can, so an upgrade can be undone by swapping the jar.
- **Seasons.** Past seasons' spawns can be recorded by `/season spawn record`; the rule that a spawn sits outside every zone applies to the season in progress and the next one only.
- **Commands and permissions.** The new commands and nodes above; the 1.1 link forms stay until at least 2.0.
- **Visiting the Hall of Fame.** "`/hof <season>` … lands at that gallery's … entry anchor" covers legacy galleries; the Legacy galleries paragraph says they are visited like any gallery, though nothing is drawn there.
- **Anchors and outlines.** Outlines (276) draw every anchor with a marker and label, not only entries and gates; the wand picks an anchor up and puts it down within its cell (amending 227, where the wand only sets and removes); `unanchor` takes `#n`.
- **Acceptance scenarios and milestones.** A77 to A95 and M8 in the milestone list, after M7.

## Decisions taken, worth a look

1. **Season 1's and 2's spawns are inside the locked old world**, so links to them stay refused until Homecoming, as the spec says today. A per-link override is deliberately not offered; holders of `seasons.zone.bypass` already pass. Say if you want links to past spawns open before Homecoming.
2. **Commands stay under `/hof link` and `/hof point`**, not a new `/link`, to avoid clashing with other plugins' `/link` and Seasons Web's `/web link`.
3. **Dialogs for the editor and text styling**, now that Paper 26.3 no longer marks them experimental, with a chest-menu fallback. Picking colours for part of a line goes through MiniMessage tags the colour buttons insert, since a dialog has no text selection.
4. **Command blocks move players only.**
5. **Sneak-right-click opens the editor only for link admins**, with either an empty hand or the wand; for everyone else it takes the link.
6. **The upgrade changes one behaviour by default:** links on buttons, signs, doors and other clickable blocks become click-only; plates, floor blocks and areas fire as before, and every link stays open to everyone. `links.upgrade.keepStepping: all` changes nothing at all, if you'd rather review link by link first.
7. **Going back is a jar swap,** because `hall.yml` is left as it was and no schema version moves. The cost is a frozen copy of the old links in `hall.yml` until `/hof link upgrade forget-old`.
8. **The 1.1 command forms keep 1.1's behaviour** (including stepping on non-clickable blocks), so scripts, command blocks and the existing scenarios don't change; the new grammar and the wand use the new defaults.
9. **Dragging keeps an anchor in its own cell,** and leaves the block under an archive or plaque anchor where it is unless "carry the block too" is on and the block is empty, so a drag never moves items or empties another cell by accident.
