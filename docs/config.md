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

### `admin`

Admin conveniences.

| Key | Default | Description |
| --- | --- | --- |
| `admin.hints` | `true` | After a setup step, suggest the commands that usually come next. |

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

