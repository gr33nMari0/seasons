# Configuration reference

Generated from the code by `./gradlew :core:configReference`; don't edit it by hand. Each setting's description is its `@Comment` in `core/src/main/java/com/petepeterepeat/seasons/core/config/`, and the same text is written as comments into the bundled `config.yml` and `secrets.template.yml`.

Seasons keeps human-owned settings in `plugins/Seasons/`. Edit a file, then run `/season reload`: every file is checked first, and on any error the old settings stay in force and the error (file, line, message) goes to the console and online admins. `/season config get|set` reads and changes `config.yml` in game. A file only needs the keys it changes; everything else takes the default below. Durations are written like `7d`, `1h30m`, `10m` or `10s`. Command-edited files (each season's file, galleries, `hall.yml`, `museum.yml`) are written by the `/season` and `/hof` commands and aren't listed here.

## `config.yml`

### `schedule`

When the season ends and how players are reminded.

| Key | Default | Description |
| --- | --- | --- |
| `schedule.autoEnd` | `false` | false: the season ends only when an admin runs /season end, whatever the planned end says. true: the end countdown starts by itself at the planned end (or 5 minutes after the next start, if the server was down then). |
| `schedule.reminders` | `[7d, 1d, 1h, 10m]` | How long before the planned end each reminder goes out, in chat and on Discord. |
| `schedule.endCountdown` | `10m` | The boss-bar countdown /season end starts. /season end --now skips it. |

### `launch`

Launching the next season.

| Key | Default | Description |
| --- | --- | --- |
| `launch.requireManual` | `true` | The next season starts only with /season launch or the launch button in the Hall. |
| `launch.setWorldSpawn` | `true` | true: launch moves the world spawn to the new season's spawn. false: it stays where it is, such as at the Hall spawn, so first-time joiners and bedless respawns arrive in the hub. |

### `archive`

Archiving at season end.

| Key | Default | Description |
| --- | --- | --- |
| `archive.arrival` | `cell` | Where archiving sends everyone at season end: cell (their own cell) or hub (the Hall spawn). One of `cell`, `hub`. |

### `resets`

What archiving resets for the new season.

| Key | Default | Description |
| --- | --- | --- |
| `resets.xp` | `reset` | Experience: reset to zero, or kept. One of `reset`, `keep`. |
| `resets.enderChest` | `vault` | The ender chest: vault (moved to the player's ender vault, which they can take from), archive (archived with the rest of their gear) or keep. One of `vault`, `archive`, `keep`. |
| `resets.respawnPoint` | `clear` | The bed or anchor a player respawns at: cleared, or kept. One of `clear`, `keep`. |
| `resets.effects` | `clear` | Potion effects: cleared, or kept. One of `clear`, `keep`. |
| `resets.advancements` | `archive-and-reset` | Advancements: archived (the plaque shows the count) and reset, or kept. Recipe unlocks are never archived. One of `archive-and-reset`, `keep`. |
| `resets.recipes` | `keep` | Recipe unlocks: kept, or reset. One of `keep`, `reset`. |

### `cells`

Hall of Fame cells: who gets one, and how visitors get in.

| Key | Default | Description |
| --- | --- | --- |
| `cells.assignment` | `random` | How cells are given out when the decorating window opens: random (a random free cell each), claim (players pick one with /hof claim), ordered (the free cells in the order they were tagged) or manual (admins assign with /hof cell assign). One of `random`, `claim`, `ordered`, `manual`. |
| `cells.minPlaytime` | `0` | Play time a player needs in the season to get a cell. |
| `cells.access` | `open` | How visitors get into a cell: open (walk in), bounds (refused at its edge while locked) or gate (a gate fills the doorway). One of `open`, `bounds`, `gate`. |
| `cells.containers` | `[chest, barrel, shulker_box, decorated_pot]` | Containers owners may place in their cell during decorating; view-only after the lock. |
| `cells.gateBlock` | `iron_bars` | With access: gate, what a cell's gate plane is while it is shut; air while it is open. |
| `cells.gates` | `open` | open: a gate is open unless an admin (/hof cell gate, /hof gallery gate) or its owner (/hof gate) shut it. auto: shut whenever its cell is locked, open while its owner decorates. One of `open`, `auto`. |
| `cells.moveOut` | `false` | false: nobody is ever moved out of a cell; a shutting gate waits until the cell is empty, and a bounds cell only refuses entry at its edge. true: they are moved to the gallery's entry. |
| `cells.protectShell` | `true` | true: where a cell's bounds take in its walls, floor or roof (selected from outside rather than inside), its owner can't break them while decorating; only builders can. |

### `decorating`

The decorating window, when owners arrange their cells.

| Key | Default | Description |
| --- | --- | --- |
| `decorating.opensBefore` | `7d` | The decorating window opens this long before the planned end (or with /season decorate open). |
| `decorating.lockAtEnd` | `true` | Owners stop decorating once their cell locks: afterArchive after their archive, or at launch if that is sooner. |
| `decorating.afterArchive` | `15m` | Time owners get to arrange their cell once archived; then it locks. 0 turns it off. |

### `pets`

Pets that travel with their owner to the new spawn at launch (/pet add).

| Key | Default | Description |
| --- | --- | --- |
| `pets.enabled` | `true` | Whether players can register pets with /pet add to travel with them at launch. |
| `pets.maxPerPlayer` | `5` | How many pets each player may register, 0 to 10. |

### `firsts`

Season firsts: the first player each season to do or find something.

| Key | Default | Description |
| --- | --- | --- |
| `firsts.rareItems` | `[heavy_core, totem_of_undying, trident, nether_star]` | Items whose first pickup each season is a season first. |

### `hof`

Visiting the Hall of Fame with /hof.

| Key | Default | Description |
| --- | --- | --- |
| `hof.entry` | | Where and when /hof works. Holders of seasons.hof.anywhere (ops by default) skip every check but rateLimit. |
| `hof.entry.requireBed` | `true` | Whether /hof needs a bed near at all. |
| `hof.entry.bedDistance` | `3` | How near a bed a player must stand, 0 to 16 blocks. |
| `hof.entry.anyBed` | `true` | true: any bed of any kind. false: only their own respawn bed. |
| `hof.entry.noHostiles` | `true` | Refused with monsters near, as the game refuses sleep. |
| `hof.entry.noDamageFor` | `10s` | Refused for this long after taking damage. |
| `hof.entry.onGround` | `true` | Refused while riding, gliding, falling or swimming. |
| `hof.entry.noHazards` | `true` | Refused in lava, fire or powder snow, or while burning. |
| `hof.entry.cooldown` | `0s` | The least time between visits. |
| `hof.entry.rateLimit` | `5s` | The least time between attempts. |
| `hof.returnSafety` | | Where /hof leave puts a player when their saved spot is unsafe. |
| `hof.returnSafety.hostileRadius` | `8` | A saved spot with monsters within this many blocks is unsafe. |
| `hof.returnSafety.fallbacks` | `[bed, nearby, spawn]` | Where to go instead, in order: bed (their respawn bed), nearby (the nearest safe block) or spawn (the season's spawn). |
| `hof.arrivalImmunity` | `5s` | Damage immunity after every Seasons teleport. |
| `hof.sealed` | `intermission` | What leaving the Hall by walking, falling or another plugin's teleport does. intermission: just leave, except during the Intermission, when everyone goes back to the Hall spawn. always: back to where they came from. never: just leave. One of `intermission`, `always`, `never`. |
| `hof.leaveWithoutSpot` | `season-spawn` | /hof leave with no saved spot (after a respawn or a first join in the hub): season-spawn takes them to the season's spawn, refuse refuses. One of `season-spawn`, `refuse`. |
| `hof.links` | | Links between the Hall and each season's spawn. |
| `hof.links.returnToLink` | `true` | /hof leave after a link to the Hall goes back to that link. |
| `hof.creativeBuilds` | `true` | Players in creative mode build anywhere in the Hall, as holders of seasons.hof.bypass do. |

### `links`

Hall links (/hof link): blocks, areas and floating text that take players to the Hall, a season's spawn, a named point or another link. The links themselves live in links.yml.

| Key | Default | Description |
| --- | --- | --- |
| `links.arrivalGrace` | `0s` | After arriving by a link or /hof, step and area triggers wait this long. The trigger a player lands in never fires until they leave it, so 0s (off) is usually enough. |
| `links.access` | | Who may take a new link, by where it leads: everyone, op, or permission (seasons.link.use.<id>). /hof link access changes one link. |
| `links.access.hall` | `everyone` | Links to the Hall spawn. |
| `links.access.seasonSpawn` | `everyone` | Links to a season's spawn. |
| `links.access.other` | `op` | Every other link. |
| `links.upgrade` | | Copying 1.1's links from hall.yml into links.yml, once, at the first start. |
| `links.upgrade.keepStepping` | `non-interactive` | Which copied block links keep firing when stood on: non-interactive (plates and plain blocks; buttons, signs and doors become click-only), plates-only, or all. |
| `links.editor` | | The link editor: sneak-right-click a trigger as a link admin. |
| `links.editor.sneakClick` | `true` | Sneak-right-clicking a trigger opens the editor for link admins. |
| `links.editor.ui` | `dialog` | dialog, or menu for a chest menu with typed answers. |
| `links.item` | | The sign /hof link item gives. |
| `links.item.material` | `oak_sign` | The item given: a sign of any wood. |
| `links.item.name` | `"<gold>Hall of Fame sign</gold>"` | Its name, MiniMessage. |
| `links.item.lines` | `["", "<gold><bold>Hall of Fame</bold></gold>", "<gray>right-click</gray>", ""]` | The four lines written on it when placed, MiniMessage. |
| `links.item.glowing` | `true` | Whether the lines glow. |
| `links.text` | | Floating text triggers' defaults. |
| `links.text.scale` | `1.0` | Text size, 0.25 to 4. |
| `links.text.billboard` | `center` | center turns to face each viewer, vertical turns about the up axis, fixed doesn't. |
| `links.text.background` | `"#40000000"` | Background colour, #AARRGGBB. |
| `links.text.shadow` | `true` | A shadow under the letters. |
| `links.text.fullBright` | `true` | Lit at full brightness, so it shows at night. |
| `links.commandBlocks` | | /hof link send <targets> <link> from command blocks. |
| `links.commandBlocks.enabled` | `true` | Command blocks may run /hof link send. |
| `links.commandBlocks.checks` | `entry` | Which checks /hof link send applies by default: none (only locked land and the Intermission), entry (the link's access and /hof's checks but the bed), or full. |
| `links.commandBlocks.playersOnly` | `true` | Only players are moved; other entities a selector picks are left. |
| `links.styleTags` | `[color, gradient, rainbow, decorations, reset, shadow_color]` | The MiniMessage tags sign and floating text may use; every other tag, click and hover included, is stripped. |

### `zones`

Zones over the old world. The zones themselves live in each season's file and are drawn with /season zone.

| Key | Default | Description |
| --- | --- | --- |
| `zones.announceToDiscord` | `false` | Also post announce-mode entries ("Peter entered the market") to the Discord webhook. |
| `zones.frontierBorder` | `false` | Draw a locked `outside` circle zone as a world border each player inside it sees: the square around the circle. Visual only: the zone does the locking, and the world's own border is never changed. Players in the Hall, in another dimension or holding seasons.zone.bypass see none. |

### `dragon`

The ender dragon and its egg.

| Key | Default | Description |
| --- | --- | --- |
| `dragon.newEggEachSeason` | `true` | Launch clears the End's previously-killed flag, so each season's first dragon kill drops a new egg. |
| `dragon.eggTo` | `slayer` | Where that egg goes: slayer (to the player who killed the dragon, with a fanfare, or to their vault's Returned page if they can't take it) or portal (it stays on the exit portal, as in the game). One of `slayer`, `portal`. |

### `deaths`

The deaths display.

| Key | Default | Description |
| --- | --- | --- |
| `deaths.display` | `list` | Where each season's deaths objective (seasons_deaths_s3) is shown: list (the tab list), sidebar, below_name or none. A new objective is made at each launch; earlier ones are kept. |

### `curate`

Curation (/hof curate): every action, museum, return, take or destroy, is audited, posted to the admin webhook and told to the owner.

| Key | Default | Description |
| --- | --- | --- |
| `curate.requireReason` | `true` | The curator types a reason in chat for each action, which nobody else sees; without it, "no reason given" is recorded. |

### `wand`

The Hall admins' wand (/hof wand).

| Key | Default | Description |
| --- | --- | --- |
| `wand.item` | `blaze_rod` | The item /hof wand gives, marked as Seasons' own, so a plain one does nothing. |
| `wand.outlines` | | The Hall's parts outlined for an admin alone while they hold the wand, or after /hof show on. |
| `wand.outlines.range` | `48` | How far away a box is still drawn, in blocks. |
| `wand.outlines.max` | `64` | How many boxes at most, nearest first. |
| `wand.nudge` | | Moving a face of a box with the wand: left-click a box to take it with the face clicked; sneak and scroll moves that face by a step; drop (Q) cycles the steps. |
| `wand.nudge.steps` | `[1, 10]` | The steps drop (Q) cycles through, in blocks. |
| `wand.nudge.swapHandsApplies` | `true` | Swap-hands (F) applies an edited box, as /hof sel apply does. |
| `wand.anchors` | | Anchors drawn with the wand. |
| `wand.anchors.markers` | `true` | Every anchor gets a coloured marker and a label while the wand is held. |
| `wand.anchors.labelRange` | `12` | How near, in blocks, an anchor's label is shown. |

### `admin`

Admin conveniences.

| Key | Default | Description |
| --- | --- | --- |
| `admin.hints` | `true` | After a setup step, suggest the commands that usually come next. |

### `logging`

What Seasons writes to the server log.

| Key | Default | Description |
| --- | --- | --- |
| `logging.startupSummary` | `true` | At startup, write a few lines saying what Seasons found: the season, the Hall, the journal, Discord and BlueMap, and whether setup needs attention. |
| `logging.debug` | `[]` | Extra lines for chasing a problem, per area: hall, archive, zones, discord, maps or commands. Leave empty normally; they can be many. |

### `api`

What Seasons API offers other plugins, such as Seasons Web's dashboard: snapshots of who's online, refreshed on the server thread.

| Key | Default | Description |
| --- | --- | --- |
| `api.refresh` | `30s` | How often who's online (dimension, area, AFK) is refreshed, besides at every join and leave. |
| `api.afkAfter` | `5m` | How long a player may be idle before they show as AFK. |

### `awards`

Awards, worked out when the season ends and announced at the Intermission. Each winner gets a trophy (item) on a free trophy anchor in their cell; ties share the award, and an award whose best is 0 goes to nobody. measure is one of deaths, travelled, play-time, diamonds, dragon, furthest-builder (from the season spawn), advancements, or any General-tab statistic id such as minecraft:jump; off turns an award off. A new id adds an award, named in messages.yml under awards.names, or after its id.

| Key | Default |
| --- | --- |
| `awards.most-deaths` | `{ measure: deaths, item: skeleton_skull }` |
| `awards.furthest-travelled` | `{ measure: travelled, item: compass }` |
| `awards.most-dedicated` | `{ measure: play-time, item: clock }` |
| `awards.deepest-pockets` | `{ measure: diamonds, item: diamond }` |
| `awards.dragon-slayer` | `{ measure: dragon, item: dragon_head }` |
| `awards.furthest-builder` | `{ measure: furthest-builder, item: spyglass }` |
| `awards.most-advancements` | `{ measure: advancements, item: knowledge_book }` |

Each entry:

- `measure`: What the award counts; off turns it off.
- `item`: The item each winner's trophy shows.

## `secrets.yml`

Webhook URLs, kept apart so `config.yml` can be shared. The environment variables `SEASONS_DISCORD_WEBHOOK` and `SEASONS_ADMIN_WEBHOOK` override them. URLs must use https; an empty value turns a webhook off. Never logged or echoed.

| Key | Default | Description |
| --- | --- | --- |
| `discordWebhook` | `""` | Discord webhook for reminders and announcements. |
| `adminWebhook` | `""` | Discord webhook for admin alerts (tripwire hits, recovery runs, reconcile repairs). |

