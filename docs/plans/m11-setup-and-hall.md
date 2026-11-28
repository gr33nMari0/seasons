# M11: Setup wizard, zone tools, Hall and museum fixes

Status: **approved**, 2026-11-27. The open questions were decided as recorded under Decisions taken. Ships in **1.6.0**. Part of `docs/plans/roadmap.md`. The Hall fixes marked **(1.4.1)** ship first, as a patch release ahead of M10.

The goal is to make setup and maintenance easy, low-context, guided and short. An admin installing Seasons for the first time should never feel lost, and nothing they do during setup should be impossible to undo.

## Decisions taken (owner, 2026-11-26)

| Topic | Decision |
| --- | --- |
| Hall patch | H1, the H2 diagnosis and fix, and H4 ship first as 1.4.1 |
| Web writes | None in M11. The wizard runs in in-game dialogs, and the dashboard shows the checklist read-only. Map-drawn zones become paste codes (Z3) |
| Default preset | **Friendly** for new installs. Existing servers keep their current, Strict, behaviour on upgrade until the admin picks a preset |
| Sandbox | Built from the owner's Hall, exported as structure templates with names, skins and signs removed. It's built by CI and never committed as a world |

## Goals

1. **A defined setup phase** with a clear start and end, a wizard that asks plain questions, and a checkpoint and rollback system so a first setup can't be ruined.
2. **Safe import of past seasons** for servers that already ran seasons by hand.
3. **Zones made easy:** from waypoints, by walking corners, or drawn on the map, plus groups and masks ("this square minus that circle").
4. **A friendlier, sturdier Hall:** harmless interactions allowed and configurable, cells that can't be broken out of, text that doesn't clip into blocks, late returners who get their cells, owners who place their own figure.
5. **A museum that's a first-class place,** set up by command, with `/museum` to visit.
6. **A practice world** to learn on.

## Non-goals

- No web writes. The wizard runs in game (Paper dialogs, as the M8 link editor does). The dashboard shows the setup checklist read-only. Map drawing on the web produces a **paste code** that the admin confirms in game (§Z3). Full web setup waits for M15.
- No undo of players' own building. Rollback covers Seasons' files, and the blocks Seasons itself places (stamps, gates, markers).

## Part S: the setup phase and wizard

### S1. The setup phase

- A new state before the first season, **Setup**. A fresh install starts in it, and so does a server that has never run `/season init` or a first launch.
  - The setup state and its checkpoints live in `<world>/seasons/setup.json`.
- **In Setup:**
  - Every Seasons change is allowed, and nothing is archived or enforced. Zones are drawn as previews only, unless the admin turns enforcement on to test.
  - The wizard and `/season setup` are the entry points.
  - Rollovers and launches are refused, and the reply says to finish setup first.
- **Leaving Setup:** `/season setup finish` runs every check (S5). It's refused while a check fails, and lists the warnings and asks for confirmation. It records the end of setup in the audit log. From then on the normal rules apply.
- **Existing servers** upgrading to 1.6 are treated as already set up. `/season setup` still works for them, as a checklist and wizard to revisit.
- `/season setup reopen` returns to Setup only while no season has been archived by Seasons, and only for server operators.
- **Spec amendment:** a new phase before Active (lifecycle and glossary).

### S2. Checkpoints and rollback (setup transactions)

- `/season setup checkpoint [name]` snapshots Seasons' files: `plugins/Seasons/*.yml`, `<world>/seasons/**` and the Seasons Web config.
  - Each block change Seasons itself makes from then on (stamps, gates, anchor markers, imports' block changes) is recorded with the block's previous state, as `/hof undo` already does for Hall edits.
- The wizard takes a checkpoint automatically at the start of each step.
- `/season setup rollback [name]` restores the files and puts back the recorded blocks. It refuses if a player-made change touched those blocks since, and lists them. Then it reloads and re-renders. `--dry-run` first.
- **Imports** (§S4) are already journalled and undoable on their own (`/hof import undo`). The rollback calls those undos and doesn't copy item data.
- Checkpoints are kept until setup finishes, then pruned to the last one, kept for 30 days.

### S3. The wizard

- `/season setup` opens a dialog. It's the same tree in the console, as numbered chat prompts. Each step is short:
  - It explains in one or two sentences what this part does.
  - It asks 1 to 3 questions with sensible defaults.
  - It shows what it's about to do, each action tagged **safe**, **undoable** or **permanent**.
  - Then it does it, or hands the admin a tool (a wand mode, a command to click) and waits.
- Progress is kept, so closing the dialog and reopening it continues where it stopped. The dashboard's admin page shows the same checklist with each step's state (R9 in M10).

| Step | Asks | Does |
| --- | --- | --- |
| 1. Welcome | Is this a new server, one mid-season, or one that ran seasons by hand before? | Explains the format in a short card. Shows where data lives (`<world>/seasons`, backed up with the world) and asks for a backup first. Takes checkpoint 0 |
| 2. Preset | Friendly, Strict or Minimal? (S6) | Writes the preset's settings into `config.yml`, with comments |
| 3. Season number | Which season is in progress? | `/season init <n>` (permanent once a season is archived, undoable before) |
| 4. Past seasons | Do you have hand-built Halls? Import, keep as legacy, or none? Where were past seasons' spawns? | Runs the import dry run (S4) and records past spawns (`/season spawn record`) |
| 5. Hall | Build your own, stamp from a template, or tag an existing build? | Hub, Hall spawn, galleries, cells (rows, stamps), anchors, with the wand at each step and `/hof hall check` after each |
| 6. Museum | Do you want a museum? Where? | `/hof museum create` (§M1), the egg plinth |
| 7. Old world | How is old land protected until Homecoming: blocked, no-build, announce or not at all? | The zone tools (§Z), with a suggested circle from the explored area (§Z4) and a BlueMap preview |
| 8. Next spawn | Pick candidates now or later? | Spawn candidates, a check that they're outside every zone and within stronghold reach |
| 9. Players' stuff | Ender chests (vault, archive or keep), pets (on and the limit), a dragon egg each season | Settings |
| 10. Integrations | BlueMap, Discord webhooks (into `secrets.yml`), Seasons Web | Settings, plus `/web check` if Seasons Web is installed |
| 11. Review | | Runs all the checks (S5), lists what's left, and offers **Finish setup** |

- **Next steps everywhere.** `admin.hints` already offers the next commands. In Setup they follow the wizard's order, and `/season setup next` prints the single next thing to do.

### S4. Safe import of past seasons

- Extends `/hof import`. The wizard walks through tagging, the dry run, fixing owners and the import, as the spec's six steps already describe. It shows the dry-run report as a readable list with a click on each flagged cell (teleport there).
- **Before importing it always:**
  - requires a checkpoint
  - recommends a world backup, and asks the admin to confirm one exists (the plugin can't verify it)
  - offers to run the import on a **copy** of the world first. This is a documented procedure, since copying worlds from a plugin isn't safe while the server runs.
- **Legacy** stays an option for a wing that should stay hand-built.
- Past seasons' spawns and player lists (who played Season N) can be entered, so links to past seasons and the history views work.

### S5. Setup checks (`/season setup check`)

One list of every check: Hall, zones, spawn, museum, integrations, Seasons Web. Each line says pass, warn or fail, what it found, and the command that fixes it. It reuses `/hof hall check`, the end dry run's checks and `/web check`. Examples:

- "Cell s3:c4 has no figure anchor. Run /hof cell anchors s3:c4"
- "Cell bounds include walls (s3:c1 to c12). Run /hof gallery shrink s3 1" (§H1)
- "Spawn candidate north is inside zone old-world"
- "No egg plinth for Season 3"

### S6. Presets

| Preset | For | Settings |
| --- | --- | --- |
| **Friendly** (recommended for friends' SMPs) | Trusting groups | Harmless Hall interactions on (§H3). Item exchange in the hub after Homecoming. Zones `block`. Gates open |
| **Strict** | Larger or public servers | The current spec defaults: deny by default, no exchange |
| **Minimal** | Trying Seasons out | Archive and Hall only. No zones, vault `keep`, pets off |

A preset only writes settings; every value can still be changed. `/season config preset <name> --dry-run` shows the diff.

### S7. `/season config upgrade` and `/season config check`

These are the same as `/web upgrade` and `/web check` (M10 §O2 and §O3) for Seasons' own `config.yml` and `messages.yml`: a backup, missing keys added with comments, placeholders, and a diff with `--dry-run`. Startup says when new settings exist.

## Part Z: zone tools

### Z1. More shapes

The spec's shapes are `box`, `cylinder`, `outside`, `y-above` and `y-below`. New ones:

- **`polygon`:** a 2D outline of up to 64 points, with an optional y range. It's for an irregular old-world edge.
- **`union` and `difference`:** a zone made of other shapes, such as "this 10,000 square **minus** this circle". Written in the zone file as `shape: difference`, `of: [box …]` and `minus: [cylinder …]`. They nest, to a depth of 3.
- **Groups.** `group: old-world` on several zones means they lock, unlock, announce and appear on BlueMap together, with one message. `/season unlock <group>` works like a zone id.
- **Geometry and performance.** Lookups stay O(zones) per check, with a bounding box first and point-in-polygon after it. A43 is widened with polygons and differences.

### Z2. Ways to draw

- **Between waypoints:** `/season zone add <id> polygon from <point|marker> <point|marker> …` uses named points (M8) or BlueMap markers as corners.
- **Walk the corners:** `/season zone draw <id>` starts a draft, `/season zone corner` adds the spot where the admin stands (walk, fly or teleport between them), and `/season zone done` closes it. Each corner and edge is drawn with particles, seen only by that admin, and the draft is shown on BlueMap as a dashed area.
- **The wand:** as now, for boxes. Plus a mode for polygon corners.
- **From the explored area:** see Z4.
- **Always a preview.** Every new or changed zone is a draft until `/season zone confirm`. The draft is on BlueMap, `/season zone info` shows its area and what it covers (spawn, Hall, candidates), and `/season zone discard` drops it.

### Z3. Drawn on the dashboard's map

The admin page of the dashboard gets a zone editor over the BlueMap view:

- The admin draws polygons and circles, then picks union or difference.
- It's drawn in the browser only and produces a **zone code** (compressed and checksummed, like M9's share codes).
- The admin pastes it in game, `/season zone paste <code>`. That creates a draft (Z2), to confirm as usual. The web never writes.

**Open question:** does BlueMap's embedded view let the dashboard read map coordinates from clicks? Framing is cross-origin, so probably not. The fallback is a plain canvas from BlueMap's own tile images, which needs BlueMap's CORS headers set. This is a spike, and if it fails, Z3 moves to M15.

### Z4. Suggested old-world zone

- Scans the region files' names in each dimension's `region/` folder (file names only, on a worker thread) to find the generated area. It suggests a circle or polygon around it, with a margin, as a draft.
- An option weights it towards chunks players actually changed: Paper's `Chunk#getInhabitedTime()` for loaded chunks, or a sampled scan done slowly in the background with a progress bar.
- The admin always reviews the draft on BlueMap. It's only a suggestion.

## Part H: Hall fixes

### H1. Cells that can't be broken out of (1.4.1)

- **About last time:** yes, the cell selection was the cause. A cell's bounds are its **interior** (spec: "the floor itself not included"). Selecting from the outside put the walls, floor and roof inside the bounds, and owners may break anything in their bounds while decorating. Selecting the interior (the 7 × 7 × 6 air above the floor) fixes both problems. Owners can still place blocks against the walls, floor and roof, because the placed block lands in the interior.
- **To make the mistake hard to repeat:**
  - **Detection.** `/hof hall check` and the wand warn when a cell's outer layer is mostly solid blocks: "Cell s2:c3's bounds look like they include its walls."
  - **Fix in one step.** `/hof cell shrink <cell> [n]` and `/hof gallery shrink <gallery> [n]` shrink each face by n blocks, with a preview and an undo.
  - **Shell protection** (`cells.protectShell: true` by default). The outermost layer of a cell's bounds is builder-only if it's solid. Owners can't break it, even if the bounds were drawn too big. This is a safety net, not a replacement for correct bounds.
- **Spec amendment:** none for the first two. Shell protection is a new setting.

### H2. Late returners and placeholder figures (diagnose in 1.4.1)

**What happened:** players from the old season who were offline at rollover came back and had no cell, or no figure. Only players online during decorating had them.

**First, reproduce.** The spec says everyone who played the season gets a cell when decorating opens, online or not, and anyone else at their next login or at archive time. A scenario covers:

- (a) offline at the end and back after launch
- (b) offline at the end and back during the Intermission
- (c) a cell assigned, but the player not yet archived, so there's nothing to draw

The likely cause is (c): the cell is assigned, but a figure is only drawn from an archive, which only exists after their login catch-up. So the cell looks empty until then. Whatever the cause, it's fixed in 1.4.1.

**Then, in 1.6:**

- **Placeholder figures.** A cell assigned to a player not yet archived shows a mannequin with their skin and no gear, with the plaque "Alex · Season 2 · arriving soon" (`cells.placeholder: true`). Their login catch-up archives them and replaces it with the real figure in the same tick.
- **Assignment after the window.** A player first archived after decorating closed still gets a free cell, and admins are told. If none are free, the existing shortfall alert applies.
- **Manual CRUD for players' cells:**
  - `/hof player <name>` shows their cells across seasons, archive state, figure and render state
  - `/hof cell assign` and `unassign` (these exist)
  - `/hof cell render <cell>` redraws one cell now
  - `/hof cell placeholder <cell> <player>` adds a placeholder by hand, for players who will never return
  - `/hof cell card <cell> edit` edits the card text of imported or placeholder cells
- **Why didn't it update?** `/hof why <cell|player>` explains, step by step, what drawing that cell needs and which step is missing. For example: "no archive yet (player hasn't logged in since Season 2)", "figure anchor blocked by a block", "chunk not loaded; it will draw when someone is near", "anchors changed but not reconciled; run /hof reconcile s2". Anchor changes trigger a redraw of that cell at once, so admins don't have to remember to reconcile.

### H3. Harmless interactions in the Hall, configurable

- **Today:** deny by default, everything except containers (view-only), anchors, plaques, exit plates, cushions, wooden doors, trapdoors and fence gates (questions 76).
- **Proposed:** an **interaction policy**, a table in `config.yml` with one rule per *kind of interaction* × *where* × *who*:
  - where: hub, hallways, own cell, others' cells, locked cells, museum
  - who: visitor, owner while decorating, owner after the lock, builder
- **Default kinds:**

  | Kind | Examples | Friendly | Strict (today) |
  | --- | --- | --- | --- |
  | `sound` | bells, note blocks played (left-click), but not tuned | Allow | Deny |
  | `ride` | sit in a boat or minecart that a builder placed. It can't be broken or pushed out of the Hall | Allow | Deny |
  | `read` | read a lectern's book (view only, never take), read a sign | Allow | Deny |
  | `redstone` | buttons and levers placed by builders | Allow in the hub | Deny |
  | `doors` | doors, trapdoors, fence gates | Allow | Allow |
  | `sit` | cushions, stairs if a seat plugin exists | Allow | Allow |
  | `craft` | crafting table, stonecutter, loom (own items only, nothing stored) | Allow in the hub | Deny |
  | `store` | jukebox, chiseled bookshelf, flower pot, armour stand, item frame (item moves) | Deny | Deny |
  | `rest` | beds (respawn point) | Deny | Deny |

  The table is named per kind, so a server can allow `sound` everywhere and `redstone` only in the hub.
- **Item exchange in the hub (`hof.exchange`).** Players dropping and picking up their **own** items in the hub, to trade.
  - Values: `off`, `after-homecoming` (Friendly), or `always`.
  - It's never allowed in galleries or cells. It's never allowed from the end countdown to launch: items dropped then could skip archiving, so any left in the hub at the countdown are given back to whoever dropped them, or to their vault's Returned page.
  - Archived items can't exist in the world, so nothing taken from a display can be traded.
  - Hall ender chests stay builder-only, so the Hall can't become a stash.
- **Spec amendment:** "Deny by default inside protected areas" stays the principle, and the policy is the configurable exception. Questions 76's list becomes the Strict preset. The visiting rule "Nothing can be stashed in the Hall or carried out of it" stays true for Hall items. Exchange moves only players' own items, in the hub, and never across a rollover.

### H4. Text that doesn't clip into blocks (1.4.1)

- **Cause:** a plaque's text display is placed at the anchor block's position. When the anchor is a wall block, or the text is wider than the space in front of it, the text sits inside blocks.
- **Fix:**
  - Anchors get a **facing** (set by the face clicked with the wand, or by `/hof cell anchor face`).
  - Text is placed 0.02 blocks in front of that face, with a fixed billboard, facing out.
  - At draw time the renderer measures the text's width and height at its scale. If they cross a solid block, it shrinks the scale to fit, down to 0.25, and warns in `/hof hall check` and `/hof why`.
  - Existing anchors without a facing get one guessed from the nearest air face, once, recorded in the gallery file.
- **Test:** a scenario with a plaque on a wall, a plaque in a 1-wide nook, and a long name.

### H5. Owners place their own figure and anchors

- **During decorating, the owner gets a "cell kit".** It's a wand limited to their own cell. With it they can:
  - see their anchors as outlines, from M8
  - see a **preview of their mannequin** (their skin, wearing what they'll be archived in, translucent) at the figure anchor
  - pick up the figure and put it somewhere else, turned the way they want, with M8's drag
  - move the plaque, archive and entry anchors
- **Owners can't:**
  - remove required anchors
  - move anchors outside their bounds
  - touch the gate
- **Required anchors (`cells.requiredAnchors`).** The default is all except gate: `figure`, `archive`, `plaque`, `entry`, and `trophy` if the player has awards. **Decision wanted**, see Feedback. Required anchors that the admin's template already has count as placed.
- **Reminders.** When decorating opens: "Build anything in your cell. Place your figure, plaque, archive and entry before it locks: /hof kit". Then on the action bar while in the cell: "Still to place: plaque". And one more reminder 5 minutes before the lock. Anything not moved stays where the template or admin put it, so nothing is ever missing at the lock.
- **The figure moves, never the items.** Picking up the figure moves the display only. No item is involved.

## Part M: museum

### M1. Set up by command, with clear rules

- **Today:** a museum is a gallery whose kind is changed to `museum` by editing its YAML (the docs' step 3), and it needs a season number it doesn't use. There's **no automatic designation**: nothing becomes a museum on its own. The docs will say so in one line.
- **New commands:**
  - `/hof museum create <id>` makes a museum with no season number
  - `/hof gallery kind <gallery> season|museum|legacy` converts a gallery, refused while it has owned cells
  - `/hof museum plinth add` and `egg <season>` place plinths and egg plinths (they're in the wand menu now)
- **Museums can be anywhere.** They count as Hall for protection and are exempt from every zone, like the Hall. That's today's behaviour for galleries, stated explicitly. `/hof hall check` stops warning about a museum not touching the hub; it notes it instead.

### M2. `/museum` to visit

- `/museum [id]` visits a museum's entry with the same entry checks as `/hof` (bed, safety, rate limit). `/museum list` lists them. `/museum leave` is the same as `/hof leave`.
- A link destination `museum <id>` (M8 grammar).

### M3. A nicer museum

- Each plinth opens a view-only item view with full tooltips, as the archive view does, and a plaque card: label, season, the story (`/museum story <anchor> <text>`), and who it came from.
- **Tour order.** Plinths numbered, with "next" and "previous" floating links (M8 floating text).
- **Wall of firsts and awards.** An optional board listing each season's firsts and awards as text. It's computed and holds no items.
- **On the dashboard:** the museum page shows plinths with stories and the egg wall.

## Part P: practice world

- **A sandbox world**, downloadable from the public site and from each GitHub release. It has a small hub, two wings (one built, one empty to practise on), a museum, a fake "old base" to zone, and the wizard ready to start.
- **Built reproducibly, never committed** (AGENTS.md: no worlds in git). A Gradle task starts a void-world scenario server and stamps the sandbox from structure templates in `templates/sandbox/`. CI zips the world and attaches it to the release.
- **Your Hall.** Your own Hall could be the model: export it as structure templates with your names, skins and signs removed. It can't be the downloadable world itself, which would carry players' builds and names onto the public site. **Decision wanted.**
- **A practice guide** in the docs: "Run your first rollover on the sandbox", with bots optional, using the scenario harness's documented commands.

## Phases and pull requests

| PR | Contents | Release |
| --- | --- | --- |
| `hall-fixes-patch` | H1 (detection, shrink, shell), H2 (diagnosis and fix), H4 | **1.4.1** |
| `m11-hall` | H2 (placeholders, CRUD, why), H3, H5 | 1.6.0 |
| `m11-museum` | M1 to M3 | 1.6.0 |
| `m11-zones` | Z1 to Z4 | 1.6.0 |
| `m11-setup` | S1 to S7 | 1.6.0 |
| `m11-sandbox` | P | 1.6.0 |

Each PR updates the docs (guide pages, references, `since.json` and CHANGELOG), as AGENTS.md requires. The Setup guide replaces "Your first season" as the start of the docs.

## Spec amendments on approval

- Lifecycle and glossary: the Setup phase, checkpoints, presets.
- Hall: the interaction policy and exchange (H3), shell protection (H1), placeholders (H2), owner-placed anchors and `cells.requiredAnchors` (H5), anchor facing (H4).
- Museum: commands, `/museum` visits, kind without a season.
- Zones: `polygon`, `union`, `difference`, groups, drafts, paste codes.
- Commands and permissions:
  - `/season setup …`, `/season config upgrade|check|preset`, the zone draft commands
  - `/hof cell shrink|render|placeholder|card`, `/hof gallery shrink|kind`, `/hof player`, `/hof why`, `/hof kit`
  - `/hof museum …`, `/museum [id]|list|leave|story`
- Milestones: M11 as above.

## Further decisions

The owner delegated these to Claude on 2026-11-27, to decide in the spirit of the mod. Each has its reason, and the owner can overturn any of them.

| Topic | Decision | Why |
| --- | --- | --- |
| Required anchors | `cells.requiredAnchors: [figure, archive, plaque, entry]`. A trophy anchor is required only when the player has an award or first to show. The gate is never required. Each anchor in a template or placed by an admin counts as placed, so no cell is ever left missing one at the lock | Every archive must be viewable and every visitor must land somewhere. Requiring a trophy anchor in a cell with no trophies would be busywork |
| Item exchange | Friendly is `after-homecoming`, in the hub only. It's never possible in galleries or cells, and never from the end countdown to launch, when anything left in the hub goes back to whoever dropped it. Strict is `off` | Before Homecoming the Hall would be a way past the old-world lock. Across a rollover it would skip archiving. After Homecoming the whole world is open, so a meeting place adds fun without a loophole |
| Sandbox scope | Export the hub, one finished wing and the museum. Leave one wing empty for practice. Strip players' names, skins, signs, books and items. Built by CI from those templates. **Needs from the owner:** a copy of the world before the `m11-sandbox` PR starts | Enough to practise every wizard step, small enough to download |
| Existing servers | Treated as already set up when they upgrade, and they keep their current (Strict) behaviour. Once, at the first start of 1.6, admins get a hint that `/season setup` exists as a checklist and that presets are available | Upgrading must never change how a running server behaves |
