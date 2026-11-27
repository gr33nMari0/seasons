# M10: Rich dashboard and web operations

Status: **approved**, 2026-11-27. The open questions were decided as recorded under Decisions taken. Ships in **1.5.0**. It is part of the roadmap in `docs/plans/roadmap.md`.

M10 makes the Seasons Web dashboard a companion that players come back to between sessions, and makes Seasons Web easy to run. It has three parts:

- **Rich data.** New widgets that help players play: biome and other advancement checklists, firsts still up for grabs, progress, history and charts. Each widget's visibility follows the permission levels.
- **Layout and look.** A map that fills its card, scrollable widgets, responsive sizing, and a colourful, consistent chart style on the `design/` tokens.
- **Web operations.** `/web reload`, `/web check`, `/web upgrade` and a self-test, plus DNS and hosting guides that start with the simplest setup.

## Goals

1. A player can open the dashboard and, in one glance, see **what to do next**: biomes not yet visited, firsts nobody has claimed, their own progress.
2. Every widget looks finished on a phone, an iPad on a stand and a 27" monitor, and fills the space it's given.
3. An admin can change `web.yml` and apply it without a restart, see every problem with it, and pick up new settings after an upgrade without reading the changelog.
4. An admin who has never set up DNS can publish the dashboard and the map with the docs alone, without Cloudflare if they want.

## Non-goals

- **No web writes.** The API stays read-only, as the spec decides for v1. Personal goals and pinned items stay in the browser (`localStorage`, like M9's layouts), with a share code. Server-side saving is M15.
- **No coordinates.** No position heatmaps and no "where is X" (spec: never coordinates). Exploration widgets show *what* was visited, never *where*.
- **No Minecraft textures** (questions 313). Biomes, mobs and items use our own icons and colours from `design/`.
- **No new runtime dependency.** Charts are small SVG modules in the dashboard, drawn with the `design/` tokens. If a library ever seems needed, it gets an ADR first.

## What players asked for, mapped

| Request | Where in this plan |
| --- | --- |
| Scrollable audit log, so a widget doesn't run down the page | §L2 |
| `/web reload`, with a YAML check and a YAML upgrade that adds new keys with placeholders | §O1 to §O3 |
| DNS instructions: play, map, seasons, `_minecraft._tcp` SRV, `_seasons` TXT. Without cloudflared first, then cloudflared and others | §D |
| A biome explorer for Adventuring Time | §R1 |
| Widgets for advancements nobody has completed yet, and a count of firsts claimed | §R2, §R3 |
| Tooltips and a companion | §R4, §L4 |
| Rich, colourful, customisable and responsive. The map frame should fill its card | §L1, §L3, §L5 |
| Practical help for players, a place to come back to | §R5 to §R8 |

## Rich data

### R1. Biome explorer (Adventuring Time)

- **Data.** The criteria of `minecraft:adventure/adventuring_time` *are* the biomes it needs. Online players are read with `Player#getAdvancementProgress(Advancement)`, using `getAwardedCriteria()` and `getRemainingCriteria()`. Offline players are read from `<world>/advancements/<uuid>.json` on a worker thread, as offline stats already are. It's public API plus a vanilla file.
- **The list is the server's.** It comes from the advancement's own criteria at startup, so a new drop's biomes appear without a release. Biomes that the advancement doesn't require (such as the Deep Dark in some versions) appear in a second "bonus" group, from `Registry.BIOME`. That second group is a reading, recorded in `docs/questions.md`.
- **Widget.** A grid of biome tiles grouped by climate (snowy, cold, temperate, warm, ocean, cave, Nether). Each tile has its colour from a new `design/` biome palette, its name, and a tick when visited. Summary: "38 of 53 · 15 to go". Filters: *to go*, *visited*, *all*. A tooltip gives each biome a one-line hint on where it's found ("Next to frozen oceans"), written by us in `messages.yml`.
- **Season firsts.** If nobody has Adventuring Time yet this season, the widget says so and shows the leader's count (by permission and privacy). A player can compare with the leader without seeing which biomes the leader visited.
- **Advancements are reset each season** (spec), so the explorer always covers the current season.

### R2. Checklists for every multi-step advancement

- R1 generalised. Any advancement with more than one criterion gets a checklist: Monsters Hunted, A Balanced Diet, Two by Two, A Complete Catalogue, Hot Tourist Destinations, Smithing with Style, and any datapack advancement.
- Biomes get the explorer layout (R1). Every other advancement gets a compact list of the criteria to go, with our icons where we have them and text otherwise.
- The names of criteria and advancements need English text from the server's translatable components. **Spike**: check whether `GlobalTranslator` with `PlainTextComponentSerializer` gives English names on Paper without bundling Mojang's language files. If it doesn't, the fallback is to humanise the ids ("minecraft:snowy_taiga" becomes "Snowy Taiga"). The result is recorded in `docs/questions.md`.

### R3. Firsts board

- Every advancement and listed rare item that counts as a season first, in three states: **claimed** (who, when), **up for grabs** (nobody yet), and **yours**.
- A headline counter: "41 of 122 firsts claimed · 9 players have at least one". A small bar shows claims per player, by visibility.
- A filter for *up for grabs*, sorted by "closest for you". That's the share of criteria you already have (from R2). Each one gets a hint.
- Live: a new first lights up through the existing event stream.

### R4. My goals (the companion)

- A player pins advancements, biomes or firsts from any widget (★). The **My goals** widget lists them with progress and the next step.
- Goals are kept in the browser per server (`localStorage`), with a share code like M9's layouts, and nothing is stored on the server (see Non-goals). Pins of things that are already done tick off and fade out.
- In-game mirror (**decision wanted**, see Feedback): `/season goals` would show the same pins. That needs the pins on the server, which is a write, so it's deferred to M15 unless you decide otherwise.

### R5. My season

A profile that's personal and visual:

- Stat tiles with sparklines: play time, distance, deaths, blocks placed and mined, diamonds. Sparklines need history (R7).
- Advancements: the count and progress against your last season.
- Your cell, your firsts and your awards so far. Each award gets a "how it's judged" tooltip.
- Comparison with your last season, from the archive record.

### R6. Server at a glance

- Online over time: a 24-hour and 7-day chart of players online (counts only, from R7).
- Season progress: days in, days to the planned end, Homecoming status, the dragon (alive, slain by, when), how many players have reached the Nether and the End.
- Activity: the busiest hours, from the same counts, so players know when others are on.

### R7. History store (Seasons Web's own data)

- Seasons Web samples, on the server thread at the existing 30 s snapshot:
  - online count every 5 minutes
  - each online player's headline stats once an hour
- Samples go to `<world>/seasons-web/history/` as small daily files on a worker thread: append-only and capped (`history.keep: 90d`, off with `history.enabled: false`).
- It's derived data, not archives: deleting it loses charts only. It's per server, and players hidden with `/web privacy` are left out of anything others see.

### R8. Other practical widgets

- **Hall of Fame map.** A floor plan of each gallery with cells as tiles, the owner's head on each, and a click through to the archive. It's generated from the gallery bounds and cells, with no world rendering.
- **Events and notes from the admins** (`web.yml` `notices:`): pinned notices such as "Rollover party Saturday 7 pm" or a build contest. They're read-only and set by the admin. The full calendar comes in M12.
- **Zones and rules, visual.** Locked zones on the map as BlueMap areas (they're already published) plus a legend widget: "what's open, what's locked, until when".

### R9. Admin data (`seasons.web.admin`, split finer)

- **Audit log**: scrollable (L2), searchable, filtered by kind and player, and paged through the API (`GET /api/v1/admin/audit?before=<cursor>&kind=&player=`).
- **Health**: TPS and tick-time charts from R7 samples, memory, and journal health.
- **Pending players and setup status**: who still needs catch-up archiving, plus `/web check`'s and M11's setup checks as a checklist.
- **Refused interactions in the Hall**, counted by kind. This helps tune M11's interaction policy.
- **Permission split** (new nodes, children of `seasons.web.admin`): `seasons.web.admin.audit`, `.health`, `.players` and `.setup`. Existing admins keep everything.

### API additions (read-only, `/api/v1`)

| Endpoint | Access | Returns |
| --- | --- | --- |
| `GET /api/v1/advancements` | Public | The catalogue: id, name, parent, criteria, first-eligible, hint keys |
| `GET /api/v1/players/{uuid}/advancements` | Own, or by visibility | Done, criteria awarded and remaining |
| `GET /api/v1/firsts?season=` | By visibility | Claimed and up for grabs, with counters |
| `GET /api/v1/history/online?range=` | By visibility | Counts over time |
| `GET /api/v1/players/{uuid}/history?range=` | Own, or by visibility | Headline stats over time |
| `GET /api/v1/hall/{season}/plan` | By visibility | The floor plan: cells, bounds as 2D tiles, owners |
| `GET /api/v1/admin/audit?before=&kind=&player=` | `seasons.web.admin.audit` | Paged entries (replaces the unpaged call; the old shape is kept for one minor version) |

`seasons-api` gains read-only snapshots for advancement progress and the catalogue, a minor version bump (1.x).

## Layout and look

### L1. The map fills its card

- **Cause.** `style.css` gives the frame a fixed `aspect-ratio: 16 / 9` outside placed layouts, and `height: calc(100% - 3.5em)` inside them. In a wide card, the 16:9 ratio makes the frame short. In a placed card, the guessed `3.5em` for the header doesn't match the real header.
- **Fix.** Every widget becomes a flex column: the header, then a body with `flex: 1; min-height: 0`. The frame fills the body (`height: 100%`). On phones the default map card is 3:4, or taller, so the map is usable.
- **Also.** A full-screen button, and a "pop out" link to the map's own site. Remember the map's last view (BlueMap's URL hash) per browser.
- **Test.** Playwright measures the frame against the card's body at the phone, iPad and desktop sizes, in both themes.

### L2. Scrollable widgets

- Long widgets (audit log, event feed, firsts, checklists) scroll inside their card. Their header and filters stay pinned at the top, with a soft fade at the bottom edge.
- On phones, where the page itself scrolls, nested scrolling is limited to a maximum height (`--widget-max-h` token), so a card never traps the page's scroll.
- **Accessibility.** Each scrolling region is focusable with a label, and keyboard scrolling works (`tabindex="0"`, `role="region"`, `aria-label`).

### L3. Sizes and responsive grid

- Widgets declare the sizes they support (S, M, L, XL, Tall) and their minimum. The layout editor from M9 offers only sizes that fit.
- **Breakpoints.** Phone: one column, widgets at natural height. iPad: 2 to 3 columns. Desktop: 4 columns. Second screen: a fixed 16:9 grid.
- New default layouts for players, admins and the second screen. Saved layouts upgrade in place: a missing widget is skipped, not an error, as in M9.

### L4. Tooltips everywhere

- A single tooltip component: hover on desktop, tap on touch screens, and focus with the keyboard. Its text is from `messages.yml`.
- Each stat says how it's counted ("Distance: walking, swimming, elytra, boats and horses"). Each first and award says how it's won, and each biome where to find it.

### L5. Colour and charts

- A chart palette on the `design/` tokens: categorical, sequential and the biome palette. It is checked for WCAG AA against both themes by `site/test/design.test.mjs`.
- Chart modules (no dependency): sparkline, bar, stacked bar, area over time, progress ring, tile grid. Each draws SVG with `<title>` and a data table for screen readers.
- Illustrations: our own pixel icons for biome groups, mob types and advancement tabs (`design/icons`), with no Mojang textures.

### L6. Demo dashboard on the public site

- `seasonsmc.petepeterepeat.com/demo/` runs the real dashboard bundle against a static sample (Example SMP, Alex and friends), so owners can try it before installing. The site's CI checks it like any page.

## Web operations

### O1. `/web reload` (`seasons.web.admin.reload`, ops)

- Reads `web.yml` and `messages.yml` again and validates them. It applies them atomically, or keeps the old ones and lists the problems.
- What applies live: visibility, links, screens, notices, messages and previews. What needs a restart: `bind` and the HTTP server's port and address. The reply says which settings changed and which wait for a restart.
- Sessions are kept. Changing `sessions.lifetime` applies to new sessions.

### O2. `/web check`

Validates without applying. Then it runs a **self-test** of the setup, with each line marked pass, warn or fail and a fix to try:

- **Config.** Unknown keys (with a "did you mean"), keys that are deprecated or moved, values out of range, and placeholders still unfilled (`[fill me]`).
- **Address.** `public-url` is https and is the same host that `/.well-known/seasons.json` reports.
- **DNS.** The game address has an A or AAAA record or an SRV record. `_seasons.<address>` TXT points at `public-url`. This uses the JDK's DNS lookup on a worker thread, with no dependency.
- **Reachability.** The server fetches its own `public-url/.well-known/seasons.json` from outside (through public DNS), which proves the tunnel or proxy works. It's an outbound request to the server's own address only.
- **Map.** BlueMap reachable at the configured URL. Its `frame-ancestors` header allows the dashboard, or the dashboard falls back to a link.
- **Proxy.** `proxy.trusted` and `client-ip-header` look right for the request just made.

The same report appears on the admin dashboard (R9). `/web check --verbose` shows the raw values.

### O3. `/web upgrade` (and `/season config upgrade` in M11)

- When a new version adds settings, `web.yml` isn't changed on its own. At startup the log and online admins get one line: "web.yml is missing 4 new settings; run /web upgrade to add them".
- `/web upgrade --dry-run` shows the diff. `/web upgrade` then does these steps:
  1. writes a backup (`web.yml.bak-<version>`)
  2. adds every missing key with its default and its comment, keeping the admin's values, order and comments (SnakeYAML round trip, ADR 0005)
  3. marks values only the admin can fill with `[fill me]`, which `/web check` reports
  4. bumps `schema-version` through the usual migrations
- Keys that were removed are commented out with a note, never deleted.

### O4. Commands and docs

- The `/web` admin commands are listed in the generated command and permission references, with guide pages and `since.json` entries, as AGENTS.md requires.

## DNS and hosting docs (§D)

New guide group **"Addresses and hosting"** under `site/src/content/docs/docs/guides/hosting/`. Each page is task-first, in the second person, with generic examples (`play.example.com`, `203.0.113.10`):

1. **Overview: the records you need.** A table and a diagram.

   | Name | Type | Value | Why |
   | --- | --- | --- | --- |
   | `play` | A (and AAAA) | `203.0.113.10` | Where players join. DNS only, never proxied |
   | `_minecraft._tcp.play` | SRV | `0 5 25565 play.example.com` | So nobody types a port. It's only needed when the port isn't 25565, and is harmless otherwise |
   | `map` | A, or CNAME to your proxy or tunnel | | BlueMap |
   | `seasons` | A, or CNAME to your proxy or tunnel | | The dashboard |
   | `_seasons.play` | TXT | `v=seasons1; url=https://seasons.example.com` | Lets players find the dashboard from the game address |

2. **Recommended: a reverse proxy on your own machine (no Cloudflare needed).** Open ports 80 and 443. Run Caddy, which gets certificates by itself, with a 6-line `Caddyfile` for `map` and `seasons`. Point A records at your IP. An nginx with certbot variant follows. It also covers router port forwarding, home IPs that change (dynamic DNS), and what to do if your ISP blocks 80 or 443 (go to page 4).
3. **On a game-server host (panel).** Use the panel's extra ports, a subdomain the host provides, or the host's own proxy feature. Includes the Origin Rule approach from the spec, for hosts behind Cloudflare.
4. **Cloudflare Tunnel** (cloudflared), as the spec's hosting example has it: no open ports, set up step by step.
5. **Other tunnels.** Tailscale Funnel and similar, briefly, with what to check: https, WebSocket and SSE keep-alive, and the client-IP header for `proxy.client-ip-header`.
6. **Check it.** Run `/web check` and read each result.

The page order follows the request: approaches without cloudflared come first, and cloudflared and the others below. The `publish-dashboard.md` page becomes part of this group (the `_seasons` record). Its old URL redirects.

## Phases and pull requests

| PR | Contents | Checks |
| --- | --- | --- |
| `m10-web-ops` | O1 to O4, §D docs, L2 | Unit tests for reload, check and upgrade (round trip keeps comments). Scenario `m10-web-reload`. Site checks. |
| `m10-layout` | L1, L3 to L5, chart modules, demo (L6) | `layout.test.mjs`, Playwright sizes for the map and widgets, axe, token contrast |
| `m10-data` | R1 to R9, API additions, seasons-api snapshots, history store | `WebTest`, scenarios `m10-biomes` (a bot visits biomes, the explorer ticks), `m10-firsts`, `m10-history`, `m10-admin-audit` |

Acceptance scenarios get numbers A96 onwards when the plan is approved.

## Spec amendments on approval

- Web companion: the widgets table gains the biome explorer, checklists, firsts board, my goals, history charts, the Hall floor plan and notices. The API table gains the new endpoints. Admin access splits into the new nodes.
- Commands: `/web reload`, `/web check` and `/web upgrade`.
- Configuration: the `web.yml` keys `notices`, `history.enabled`, `history.keep` and `history.sample` (sampling intervals).
- Milestones: M10 as above.

## Decisions taken

The owner delegated these to Claude on 2026-11-27, to decide in the spirit of the mod. Each has its reason, and the owner can overturn any of them.

| Topic | Decision | Why |
| --- | --- | --- |
| Goals | Kept in the browser only, with a share code. No `/season goals` until M15 | The spec keeps the web read-only in v1. Goals are a convenience, and losing them loses nothing that matters |
| Progress privacy | Others' **counts** (biomes visited, firsts, advancements) are visible to logged-in players, the same level as leaderboards. **Which** biomes and criteria someone has is visible only to that player and admins. `/web privacy` hides a player's counts too. All three are `visibility` keys in `web.yml` | It follows the spec's access levels: leaderboards and profiles need a login. Keeping the exact list private stops anyone copying another player's route to a first |
| History | Hourly headline stats per player and 5-minute online counts, kept for 90 days by default (`history.keep`), plus one end-of-season summary per player kept for good. Players hidden with `/web privacy` are left out of anything others see. Turn it off with `history.enabled: false` | 90 days covers a season with room to spare, and the season summary feeds M13's recaps. Counts only, never positions |
| Demo dashboard | In M10, as a stretch goal in the layout PR. It ships if it's ready and waits for 1.5.x if not | It's cheap once the sample data exists, which the Playwright tests need anyway |
| Other widgets | None beyond the plan until players ask. Requests go in `docs/questions.md` | Keeps M10's scope fixed |
