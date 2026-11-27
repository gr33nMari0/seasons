# Seasons: long-term strategy and roadmap

Status: **approved**, 2026-11-27. Nothing here changes a decision in `docs/spec.md` until the owner approves it. Where a milestone needs a spec change, the plan lists it under "Spec amendments", and the spec changes only once that milestone's plan is approved.

Detailed plans: `docs/plans/m10-rich-dashboard.md` and `docs/plans/m11-setup-and-hall.md`. M12 and later are outlined here and get a full plan when they are next.

## Where Seasons is today (1.4.0)

- **Plugin (M0 to M8).** Rollover with the journal, the Hall with its hub, galleries, cells, anchors and links, old-world zones and Homecoming, the vault, pets, the dragon egg, the museum, curation, stats, firsts, awards, Discord, BlueMap, the wand and templates.
- **Seasons Web (M7, M9).** A read-only dashboard and API, device-code login, the connect page, finding a dashboard from the game address, customisable layouts kept in the browser, rich previews, and one design system shared with the site.
- **Public site and docs (M9).** Astro and Starlight, generated references, and CI checks that keep the docs in step with the code.

What's missing is mostly what happens **around** the features:

- First-time setup takes a lot of context.
- The dashboard shows data but doesn't yet help players play.
- A few Hall behaviours are stricter or more fragile than real servers need.
- Reach is limited to Paper and BlueMap on the newest drop, in English.

## Vision

> **Seasons is the season format for any survival SMP.** An admin can set it up in an evening without reading the spec. It never loses an item. Players come back to its companion site between sessions to plan what to do next.

### Who it's for

| Who | What they need | Where they struggle today |
| --- | --- | --- |
| **Owner-admin** of a friends' SMP, often the only technical person | Setup that's guided and reversible. Clear next steps. Upgrades that don't break things. | Zones, cells and anchors take a lot of knowledge. YAML edits are needed for some settings, such as the museum kind. DNS and the tunnel are hard. |
| **Player** | Know what's going on and what to aim for. Show off their season. | The dashboard shows status, but doesn't help them chase goals. |
| **Builder** who decorates the Hall | Freedom to decorate inside clear limits | Cells selected too large let owners break walls. Interaction rules are all-or-nothing. |
| **Power admin** on a larger server or network | Integrations, permissions, scale | No PlaceholderAPI, no maps other than BlueMap, no networks |
| **Contributor** | Clear architecture, fast tests | Mostly fine. The spec is long, but AGENTS.md and the plans help. |

### Strategic pillars

Each milestone should move at least one of these forward and must not set back the third.

1. **Effortless operation.** Guided setup, self-checks, upgrades that fill in new settings themselves, undo everywhere, and diagnostics that say *why*.
2. **A companion players return to.** A dashboard that helps people play: goals, checklists, firsts still up for grabs, history and recaps.
3. **Trust.** No dupes, no lost items, every change journalled and reversible. This pillar has priority: a feature that would weaken it waits until it can be built safely.
4. **Reach.** More map plugins, permission and placeholder integrations, translations, the next game drops, and, on demand, more platforms and season formats.
5. **Sustainable maintenance.** One maintainer, assisted by agents. Generated docs, scenario tests and automated releases keep the cost of each release flat.

### Principles for future work (in addition to the spec's six)

- **Guide, don't require knowledge.** Every admin task is reachable from one place (`/season setup`, the dashboard's admin page), and every error says what to run next.
- **In-game is the authority.** World and archive changes are confirmed in-game, or with an authenticated web write once M15 exists. The web never changes the world on its own.
- **Privacy by default.** No coordinates, no IPs and no tracking. Personal data is opt-out and shown to the person themself first.
- **Generic by default, the server's own flavour in its own files.** This continues the M9 rule.
- **Prefer configuration presets to new modes.** Example: "Friendly Hall" versus "Strict Hall", not two code paths.

## Milestones

| Milestone | Release | Theme | Pillars |
| --- | --- | --- | --- |
| **M10** | 1.5.0 | Rich dashboard and web operations | 2, 1 |
| **M11** | 1.6.0 | Setup wizard, zone tools, Hall and museum fixes | 1, 3 |
| M12 | 1.7.0 | Votes, events and notifications | 2, 1 |
| M13 | 1.8.0 | Season recaps, history and export | 2 |
| M14 | 1.9.0 | Reach: integrations, translations, more map plugins, API events | 4 |
| M15 | 2.0.0 | Admin web console (authenticated writes) | 1 |
| M16 | ongoing | Platforms: next drops, Folia, Fabric, Geyser, on demand | 4 |
| M17 | research | Other season formats: a new world each season, networks | 4 |
| Track | every release | Maintenance: drops, dependencies, performance, security review | 5, 3 |

The order puts **player value (M10)** and **setup pain (M11)** first. Those are what decide whether a new server keeps Seasons. M15 is late on purpose: web writes are the biggest security change since v1, and M11's wizard works without them.

### Decided: a 1.4.1 Hall patch before M10 (owner, 2026-11-26)

Three of M11's fixes hurt a live server now. They're small enough to ship as **1.4.1** before M10:

- Late returners with no cell or figure (M11 §H2): first diagnose it, then add placeholder figures.
- Owners breaking through walls, floor and roof (M11 §H1)
- Plaque text clipping into blocks (M11 §H4)

The next rollover's date isn't fixed, so the patch is treated as urgent: it's the next thing built, before any M10 work.

### M12: Votes, events and notifications (1.7.0)

The spec decides that the group votes on most things: the end date, Homecoming and the spawn. Today those votes happen outside Seasons.

- `/season vote` with kinds `end`, `homecoming`, `spawn` (on the candidates, with BlueMap previews) and `custom`. Each vote has a quorum, a deadline and anonymous or open ballots. Results appear on the dashboard, and the admin still acts by hand (the spec's manual end is kept). A passed vote can *offer* the command to the admin, never run it.
- An **events calendar** in `web.yml` (rollover party, build contest), shown on the dashboard, with an `.ics` feed and in-game reminders.
- **Scheduled announcements**, the reminder system generalised to any message.
- **Dashboard notifications.** The dashboard becomes an installable PWA with opt-in Web Push: "season ends in 1 hour", "Homecoming!", "you claimed a first". VAPID keys go in `secrets.yml`, and push signing uses the JDK only (ADR needed).
- **Deeper Discord.** Optional Discord login for the dashboard (the spec says "may come later") and vote posts with results.

### M13: Season recaps, history and export (1.8.0)

- **Season recap** ("Wrapped"). A per-player page at season end with highlights, firsts, records, a comparison with last season, and a preview image to share. Each player sees theirs first, and can make it public or keep it private.
- **Server yearbook.** A static HTML export of a finished season (Hall, cards, firsts, awards) that can be hosted anywhere, even after the server is gone.
- **History views.** Every past season side by side, records across seasons, and a player's career across seasons.
- **Data export.** A player can download their own archive as JSON or text; admins can export the whole season. This is also a backup of record.

### M14: Reach (1.9.0)

- **PlaceholderAPI** expansion: phase, countdowns, firsts, cell and stats. This is one of the most common plugin integrations.
- **LuckPerms contexts**: `seasons-phase`, `seasons-stage` and `seasons-zone`, so permissions can depend on the season.
- **Map ports**: squaremap, Pl3xMap and Dynmap beside BlueMap (behind the existing map port), and on the dashboard.
- **Translations**: per-player locale from the client and community translation files for `messages.yml` and the dashboard. The docs stay English at first.
- **seasons-api events**: `SeasonEnded`, `Homecoming`, `DragonSlain` (on hold since M4), `FirstClaimed` and `Archived`, so other plugins can react.
- **WorldGuard and CoreProtect coexistence**: guides and tests, plus a startup check that warns about known conflicts.

### M15: Admin web console (2.0.0)

- An opt-in authenticated write API. It needs a fresh in-game confirmation for each dangerous action, CSRF protection, an audit entry for everything, and a separate permission for each action. It is off by default.
- It unlocks the parts of M11 that are better on a big screen: drawing zones on the map, reviewing the setup checklist and editing `web.yml`.
- A major version because the spec's "read-only in v1" decision ends here. It needs its own threat model in `docs/security-review.md`.

### M16: Platforms (ongoing)

- **Every drop**: a version bump, the full scenario suite, and a release within a week of Paper. Automate the drop check with nightly runs against Paper's newest builds.
- **Folia**, if servers ask: the region-thread model touches every world change.
- **Fabric, then NeoForge** adapters, per the spec, when a real server asks.
- **Geyser**, after checking how mannequins and the archive view look to Bedrock players.

### M17: Other season formats (research)

These would make Seasons fit many more servers, but each is a new product decision.

- **A new world each season.** Many SMPs reset to a fresh world. Seasons would archive into the Hall (in a persistent hub world), keep old worlds visitable and read-only or unlockable, and carry pets across. The archive, Hall and web layers are reused; zones become "old worlds".
- **Networks** (Velocity): the Hall on a lobby server, seasons on a survival server, and one shared store.

A research spike first: what would change in the spec, and is the demand real? The data source is bStats plus the Hangar and Modrinth comments.

### Continuous maintenance track

- **Each drop**: see M16.
- **Dependencies**: Dependabot, monthly.
- **Performance**: keep A43 green. Add a 100-player synthetic scenario before M14.
- **Security**: refresh `docs/security-review.md` at every minor release, and in full for M15.
- **Docs**: the M9 rules (generated references, coverage and since entries).
- **Support load**: track the most common questions in the FAQ. Every repeated question becomes a check in `/season setup check` or `/web check`.

## Gaps found (beyond M10 and M11)

What would make Seasons easier to use, more useful, or fit more servers. Each one is placed in a milestone above or marked as needing a decision.

| Gap | Why it matters | Where |
| --- | --- | --- |
| No in-game voting, though the format runs on group votes | Saves a Discord poll every time and keeps the record | M12 |
| No notifications | Players miss Homecoming and the end countdown | M12 |
| No season recap or shareable history | Recaps are the best word-of-mouth for a server and for Seasons | M13 |
| No PlaceholderAPI or LuckPerms contexts | Common on SMPs. Scoreboards and tab lists want the countdown | M14 |
| BlueMap only | Many servers run squaremap, Pl3xMap or Dynmap | M14 |
| English only | Excludes many communities | M14 |
| No API events | Other plugins can't react to Seasons | M14 |
| Dashboard can't change anything | Fine for players. Admins juggle chat commands | M15 |
| A fresh world each season isn't supported | Probably the most common "season" style | M17 (decision) |
| **Licence vs. "commercial"** | Many SMPs take donations or sell ranks. It's unclear whether that's "commercial use" under PolyForm Noncommercial, and the doubt may stop adoption | **Decision for the owner**: state a position in the FAQ, for example "donations that cover hosting are fine". Get legal advice if needed |
| No demo | People can't try the dashboard before installing | M10 (a demo dashboard on the site with sample data) |
| No practice world | Admins learn on their live world | M11 (sandbox) |
| No config presets | Admins have to choose every setting | M11 (presets: Friendly, Strict, Minimal) |
| No "why didn't this happen?" diagnostics | The largest source of confusion (anchors, renders) | M11 (`/hof why`), M10 (`/web check`) |
| Upgrades don't show new settings | Admins never learn about new features | M10 (`/web upgrade`), M11 (`/season config upgrade`) |

## How we'll know it's working

No tracking of players. The signals are:

- bStats: server count and versions, plus new opt-in setup charts, such as "setup finished" and "wizard used", as booleans.
- Fewer setup questions in issues and Discord after each release.
- How long the wizard takes on the sandbox world, measured during development: **under 30 minutes from install to a first season that's ready to end**.
- The owner's own server: players using the dashboard between sessions. This is anecdotal, and that's fine.

## Decisions taken

The owner made the first four decisions on 2026-11-26. On 2026-11-27 the owner delegated the rest to Claude, to decide "in the spirit of the mod". Each of those has its reason beside it, and the owner can still overturn any of them.

| Topic | Decision |
| --- | --- |
| Hall patch | 1.4.1 ships before M10, with H1, the H2 diagnosis and fix, and H4 from the M11 plan |
| Web writes | In game is the authority until M15. The M11 wizard uses in-game dialogs, and map-drawn zones become paste codes |
| Default Hall preset | Friendly for new installs |
| Sandbox | Built from the owner's Hall, exported as structure templates with names, skins and signs removed |
| Vision and principles | As written: trust before features, in game as the authority until M15, privacy by default. These follow the spec's design principles 2 to 4 |
| Order | M12 to M15 as listed. Cheap reach items from M14 (PlaceholderAPI, translations of `messages.yml`) may come forward into any release that has room, since they touch no spec decision. M15 stays late, because web writes are the largest security change since v1 |
| M17 | A research spike only, after M14, ending in a written proposal. Nothing is built for it without the owner's go-ahead, since a new world each season changes the spec's core premise (one persistent world) |
| Licence position | Proposed FAQ wording: "Running Seasons on your server is noncommercial use even if players donate or buy cosmetic ranks to cover costs. Selling Seasons, selling access to its features, or bundling it in a paid product or hosting plan needs a commercial licence." Peter is the licensor, so he can grant this reading in writing. Before it is published, a quick legal check is worth having. Until then it stays in this plan and isn't on the site |
| Next rollover | No date, so 1.4.1 is treated as urgent and comes first |
