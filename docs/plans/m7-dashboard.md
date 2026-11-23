# M7 part 2 Dashboard and connect page: plan

Branch `m7-dashboard`, from `main` at `70960bb` (1.2.0-SNAPSHOT, M7 part 1, #36, merged). Nothing here is built yet. Once the owner agrees the plan, slice 1's first commit writes the readings below into `docs/questions.md`, numbered straight after the last entry on `main` when it lands (302 today; `docs/plans/m8-reliable-links.md` also claims 303 on, so whichever branch merges second renumbers its own).

With this part A48 passes in full and M7's two scenarios (A48, A49) are done, so M7 is done.

## What part 1 left, and what it gives part 2

Built and kept as is: the JDK `HttpServer` on virtual threads, every `/api/v1` endpoint in the spec's table, visibility per field (`Visibility`), device-code login with `__Host-` cookies, sessions, rate limits, the event stream with keep-alives, the CSP (`script-src 'self'; style-src 'self'; img-src 'self' https://textures.minecraft.net; frame-src <map and Spotify origins>; frame-ancestors 'none'`), `/.well-known/seasons.json` with CORS `*`, and `testkit/bots/lib/web.mjs`.

Missing for the widgets:

| Spec widget or field | What's missing |
| --- | --- |
| The page itself | `/` serves a placeholder (`Routes#page`) |
| Season clock's Homecoming countdown | `SeasonView` has `homecomingAt` (when it happened), nothing planned |
| Museum browser | No museum view in Seasons API |
| Season history (and the list of seasons the Hall browser picks from) | Only the current season in Seasons API |
| Admin: audit log, pending players (access levels table) | Only server health |
| Second-screen layout | No `screens:` in `WebSettings`, though the spec's example `web.yml` has it |
| Map, "a link when framing is blocked" | The map is a `links:` entry; nothing knows whether it can be framed |

## Scope

In: every widget of the spec's Widgets table on the dashboard page served by Seasons Web; login on the page; profiles with full statistics and archived gear as text; the admin widgets; second-screen mode with the Screen Wake Lock API; the public site's home and connect pages as a static site in `site/`, published by GitHub Pages; tests for all of it.

Out (unchanged from the spec, or left for later): Discord login; any write action on the dashboard (read-only in v1; `/web privacy` and `/web logout` stay in-game); the public site's docs pages beyond links to the repository's `README.md` and `docs/config.md`; Seasons Web on Hangar and Modrinth (question 292; its own small branch, as it touches only `release.yml` and the listings); translations beyond English.

Rules kept throughout: no third-party script, style or font on either site (everything from `'self'`, skins from `textures.minecraft.net`, frames only from the map's origin and `open.spotify.com`); no Minecraft texture, sound or language file bundled (items as text with our own SVG icons, heads cropped from the player's own skin on Mojang's servers); player-written text (item names, waypoint names, link labels) only ever set as text, never as HTML; no coordinates beyond what the API already serves (shared waypoints).

## Design

### Page and assets

- Plain HTML, CSS and ES modules in `web/src/main/resources/com/petepeterepeat/seasons/web/dashboard/`, served as they are: no build step, no bundler, no npm package in the jar. JavaScript files start with the SPDX line; HTML, CSS and SVG carry it as a comment.
- `Assets` (new) serves that folder from the jar: an allow-list of files read at startup, content type by extension, a strong ETag and `Cache-Control: no-cache` (so a new jar is picked up at once), `304` on `If-None-Match`, nothing outside the list (no traversal), `nosniff` as now.
- One page, `index.html`, for `/`, `/player/<uuid>`, `/hall/<season>`, `/museum`, `/history` and `/screen[/<name>]`; the script routes by path with `history.pushState`, so links can be shared and bookmarked.
- `GET /api/v1/dashboard` (new, public, cached like other public responses): the server's name, the widgets this viewer may see (visibility already decides), the map (below), the screen layouts, the viewer (`anonymous`, a player's name and uuid, or admin), and the page's texts from Seasons Web's `messages.yml` under `dashboard.*`, so owners can reword or translate them like every other player-facing line. Private for a logged-in viewer, as every response is.
- No inline script or style anywhere, which the CSP already forbids. Sizes the page computes (head crops) are CSS classes; countdowns write text only.
- DOM built with a small `el(tag, attrs, ...children)` helper that only ever creates text nodes for strings; `innerHTML` is not used anywhere (a unit test greps for it).
- Light and dark themes by `prefers-color-scheme`; second-screen mode is always dark.

### Widgets

| Widget | Reads | Behaviour |
| --- | --- | --- |
| Season clock | `/season` | Season number, phase and stage in words, a live countdown to the planned end, the end countdown when one runs, time since launch, and a Homecoming countdown when Homecoming has a date (below). Ticks every second from the server's `now`, so a wrong device clock doesn't matter |
| Online | `/players/online` | Head, name, dimension, region, AFK, as visibility allows. Never coordinates |
| Leaderboards | `/leaderboards?season=` | Deaths, play time, distance; the viewer's own row marked, and "hidden by `/web privacy`" for them when they are |
| Event feed | `/events` (stream) | Firsts, the dragon, joins, announce-zone entries, phase changes, Homecoming, archives; the last 50 kept; reconnects with backoff (1 s doubling to 30 s) and fetches the season and online list again after a reconnect |
| Map | `/dashboard` `map` | The map framed when it can be, else a card linking to it (below). A note says players show on it under BlueMap's own rules |
| Server health (admin) | `/admin/health` | TPS (1, 5, 15 min), tick time, memory, refreshed every 10 s |
| Pending players (admin) | `/admin/pending` (new) | Players waiting to be archived or held, by name |
| Audit log (admin) | `/admin/audit` (new) | The last 50 entries of Seasons' and Seasons Web's audit logs, by time, kind and who; positions left out |
| Profile | `/players/{uuid}`, `/hall/{season}/{uuid}` | Seasons played; per season a season card (play time, first and last seen, firsts, awards, every statistic, labelled in words and grouped as the card's pages are), the Hall cell with a link to that gallery, and archived gear as text: section, count, name, item, enchantments with roman levels, trim, each with one of our icons. Others' profiles show the summary unless visibility allows more |
| Hall of Fame browser | `/seasons` (new), `/hall/{season}` | A season picker, the gallery's cells with owner heads and awards, each linking to that player's archive for that season |
| Museum browser | `/museum` (new) | Exhibits with label, season, credit and the item as text; the egg wall by season with its slayer |
| Season history | `/seasons` (new) | Each season: launched, ended, Homecoming, players, firsts count, a link to its gallery |
| Rules and zones | `/season` zones, `/links` | The zones in force, each with its mode in plain words ("Locked until Homecoming", "No building", "Announced"), and any link labelled as rules |
| Waypoints | `/waypoints` | Shared waypoints by set, with their coordinates (published by the server already) and the Nether equivalent |
| Coordinate converter | none | Overworld to Nether and back, floor division so negatives are right; fills from a waypoint with one tap |
| Links | `/links` | Every link as a button; `embed: spotify` as Spotify's official embed (`open.spotify.com/embed/...` built from the playlist URL), with a plain link under it |

### The map, framed or linked

- The map is the first `links:` entry with `embed: frame` (the spec's example has exactly one). The map widget shows it; the links widget doesn't repeat it.
- Seasons Web checks on a worker, at start and every 10 minutes, whether that address lets the dashboard frame it: a `GET` with the JDK's `HttpClient` (3 s timeout, no redirects followed beyond 3, body discarded), then `X-Frame-Options` and the CSP's `frame-ancestors` read against `public-url`'s origin. `/dashboard` gives `framing: allowed | blocked | unknown`. `blocked` shows the link card only; `allowed` and `unknown` frame it with the link under it, and the page also falls back to the card if the frame hasn't loaded in 10 s.
- The check is plain header parsing (`Framing`, unit tested); it sends nothing about players, only a `User-Agent: SeasonsWeb/<version>`.

### Second-screen mode

- `/screen` shows the `second-screen` entry of `web.yml`'s `screens:`; `/screen/<name>` any other. A new `Screens` Configurate record: `layout` (`landscape`; anything else is refused with the key path) and `widgets`, by the names the spec's example uses (`season-clock`, `online`, `leaderboard`, `event-feed`, `map`, `links`, plus every other widget's name), each mapped to its visibility key; an unknown name is a warning at load and left out. With no `screens:` the default is the spec's example list.
- A fixed landscape grid sized to the viewport (12.9", 11" and 10.2" iPads in landscape, and 16:9 monitors), large type, dark theme, no scrolling; widgets the viewer can't see are left out and the rest fill the space. In portrait it asks to turn the device and shows the widgets stacked meanwhile.
- Auto-refresh: the event stream for the feed; the season every minute and at phase-change events; online and leaderboards every `api.refresh` (30 s, from `/dashboard`); health every 10 s; a full page reload once a day at 04:00 local to pick up a new jar.
- Wake lock (`WakeLock`, a small module): `navigator.wakeLock.request('screen')` on load, again on every `visibilitychange` to visible (the browser drops it when hidden), and after a failure on the next tap anywhere, since Safari may want a gesture. A small status dot shows "screen kept on" or why not; without the API (iPadOS before 16.4) it says so once and carries on.
- A second screen usually isn't logged in, so player-level widgets are left out unless it is; logging in on it works as anywhere.

### Login on the page

The page asks `POST /auth/device`, shows the code in large type with "type `/web link K7QX-2M` in Minecraft", polls at the interval given (every 2 s), and swaps to the logged-in view without a reload. An expired code offers a new one. Any `401` later (logout, expiry) returns the page to the public view with a notice. No token ever reaches JavaScript (the cookies are HttpOnly, as built).

### Seasons API 1.3 (additive)

All read on a worker from files Seasons already keeps; no new Paper API is expected (any that turns up goes in `docs/paper-api.md`).

| Addition | Holds |
| --- | --- |
| `SeasonView#plannedHomecoming` | The earliest date in `homecoming.unlock`'s conditions, if it has one |
| `seasons()` | Every season on record: number, launched, ended, Homecoming, players archived, firsts count, whether it has a gallery |
| `museum()` | Exhibits (anchor id, label, season, credit, item as `ItemView`) and the egg wall (season, slayer) |
| `pending()` | Players waiting to be archived or held as pending (questions 287), by name |
| `recentAudit(int)` | The last entries of Seasons' audit log: time, kind, actor and target names; positions and free text with positions left out |

Seasons Web then needs `>=1.3 <2.0` and says so as now. New endpoints, each with a visibility key and default: `GET /api/v1/dashboard` (always), `/api/v1/seasons` (`history: public`), `/api/v1/museum` (`museum: public`), `/api/v1/admin/pending` and `/api/v1/admin/audit` (`admin.pending`, `admin.audit`: admins). `/.well-known/seasons.json` lists the new features.

### Public site (`site/`)

- `site/index.html` (what Seasons and Seasons Web are, screenshots taken from our own scenario runs, links to the repository, `README.md`, `docs/config.md`, releases and the stores), `site/connect/index.html`, `site/connect/connect.js`, `site/style.css`, `site/icons.svg`. No script but `connect.js`, no external fonts, no analytics.
- A `<meta http-equiv="Content-Security-Policy">` (Pages can't set headers): `default-src 'self'; connect-src https:; img-src 'self'; frame-src 'none'; base-uri 'none'; form-action 'self'`. Nothing is stored in the browser and nothing is sent anywhere but the address typed.
- The connect flow (`connect.js`, pure functions plus a small form handler):
  1. Read the address: a host, `host:port` or an `https://` URL; trim, lower-case, drop a trailing path. `http://`, other schemes, user info, and anything that isn't a hostname or `host:port` are refused with a reason.
  2. `fetch('https://<host>/.well-known/seasons.json', { credentials: 'omit', redirect: 'error', signal: 8 s timeout })`. A network or CORS failure says the address doesn't answer as a Seasons dashboard and shows the https address it tried.
  3. Accept only `service: "seasons"`, `api: "v1"` and a string `name`; anything else is "not a Seasons server". Show its name, version and features, then go to the dashboard: `dashboard` when it is https on the same origin as the address typed, otherwise the address typed (never a third site a `seasons.json` names).
  4. `?server=<address>` fills the form and connects, so a server can link players straight in.
- `.github/workflows/pages.yml`: on push to `main` touching `site/**`, and by hand, uploads `site/` with `actions/upload-pages-artifact` and publishes with `actions/deploy-pages` (permissions `pages: write`, `id-token: write`, concurrency `pages`). Pull requests that touch `site/**` run the site tests in `build.yml`; `docs-only.sh` keeps treating `site/**` as code.

## Tests

### New test tooling (ADR 0007, needs the owner's agreement)

- **Playwright** (`playwright` npm, test-only, pinned, in `testkit/bots/package.json` and `site/package.json`), Chromium and WebKit, so the dashboard is driven by real browser engines and second-screen mode in the engine iPads use. Nothing of it ships. CI installs the two browsers with their system packages and caches `~/.cache/ms-playwright` by version. Without this the page half of A48 can't be shown end to end; the alternative (node-only checks of HTML strings) wouldn't run the page's script or the CSP.
- **`node --test`** for the page's and the site's pure modules: no dependency. Gradle runs it as `:web:jsTest` and `:site:jsTest` (an `Exec` task on the Node the toolchain already has), both under `check`.
- **`lib/tunnel.mjs`**: an HTTPS reverse proxy in Node (`https` and `http` modules only) with a throwaway self-signed certificate made by `openssl` into the build folder, in front of the shard's Seasons Web, adding `CF-Connecting-IP` as Cloudflare does. Browsers then see `https://localhost:<port>`, so `__Host-` cookies, the wake lock's secure context and the CSP behave as in production; it streams, so the event stream works. Scenarios set `public-url` to it and `proxy.trusted: [127.0.0.1]`.
- **`lib/browser.mjs`**: opens a Chromium or WebKit page on the tunnel (`ignoreHTTPSErrors` for the throwaway certificate), records every request's origin and every `securitypolicyviolation` and console error, and fails the scenario on any violation or request to an origin not allowed. Stubs: a small HTTPS map server with chosen framing headers, and routes for `open.spotify.com` and `textures.minecraft.net` that answer locally, so CI needs no outside network.

### Acceptance scenarios this part passes

| Scenario | Test |
| --- | --- |
| **A48, page half** (with part 1's API half, A48 in full). Without logging in, the dashboard page shows the season clock (Season 2's Intermission), who's online (Alex, with dimension and region, no coordinates anywhere on the page), the event feed (Sam's join arrives live), the map framed from its own origin, and the Hall (Season 2's gallery with Alex's cell); no leaderboards widget and only Alex's profile summary. Logged in by the code shown on the page and typed by Alex's bot, leaderboards appear and Alex's profile shows full statistics and the sword as "Edge, Diamond Sword, Sharpness V"; every logged-in response the page received carried `Cache-Control: private, no-store` | `a48-public-dashboard.mjs`, widened: the API checks stay, then Chromium on the tunnel; unit: `AssetsTest`, `DashboardRouteTest`, `WebTest`; JS: `format.test.mjs` |
| **A49** | Unchanged from part 1 (`a49-proxy-client-ip.mjs`); the tunnel also sends `CF-Connecting-IP` in every browser scenario, so the per-visitor limits are exercised through it |

### This part's own scenarios

| Scenario | Test |
| --- | --- |
| Every live widget from the API: the clock counts down to `/season schedule end` and to a dated Homecoming, shows the end countdown when `/season end` starts one, and is right with the browser's clock an hour off; online shows AFK and dimension; leaderboards rank deaths, play time and distance and mark the viewer; the feed shows a first, a phase change and an announce-zone entry; links show the Spotify embed (stubbed) with its link | `m7-dashboard-live.mjs` (Chromium); unit: `DashboardRouteTest`; JS: `format.test.mjs`, `clock.test.mjs` |
| Reference widgets: the Hall browser across Seasons 1 (imported) and 2, the museum with an exhibit and an egg-wall entry, season history with dates, the zones in force in plain words, waypoints with their Nether coordinates, the converter (including negative coordinates) | `m7-dashboard-reference.mjs`; unit: core `SeasonViewsTest` (history, planned Homecoming), `MuseumViewTest`, `WebTest`; JS: `convert.test.mjs` |
| Profiles: Alex's own full profile, a season card per season with every statistic in words, awards, firsts and the Hall cell linking to the gallery; gear as text with name, enchantments (roman levels, books' stored ones), trim and our icons; another player's profile is a summary until `profiles: public`; `/web privacy` shows as hidden on Alex's own leaderboard row | `m7-dashboard-profile.mjs`; unit: `WebTest`; JS: `items.test.mjs` (ids to names, enchantments, trims, icon categories), `stats.test.mjs` |
| Player-written text stays text: a sword named `<img src=x onerror=alert(1)>`, a waypoint and a link label with markup show literally, run no script and cause no CSP violation | `m7-dashboard-text.mjs`; JS: `dom.test.mjs`; a test failing on any `innerHTML`, `outerHTML`, `insertAdjacentHTML` or `document.write` in the page's code |
| Visibility on the page: with `online.region: hidden`, `map: hidden`, `leaderboards: public` and `event-feed: players`, the page shows and hides exactly those, and the hidden widgets' endpoints are never requested; with `public: false` a visitor sees only the login | `m7-dashboard-visibility.mjs`; unit: `DashboardRouteTest`, `VisibilityTest` |
| The map: framed when the map server sends `frame-ancestors <dashboard>`; a link card and no frame with `X-Frame-Options: DENY`, with `frame-ancestors` naming another site, and when it doesn't answer; the card also after 10 s with a frame that never loads | `m7-dashboard-map.mjs` (stub map servers); unit: `FramingTest` |
| Login on the page: the code shown, linked in-game, the page switches without a reload; an expired code offers a new one; `/web logout all` sends the page back to the public view with a notice; no cookie is readable from the page's script | `m7-dashboard-login.mjs` (Chromium and WebKit) |
| Admin widgets: a player with `seasons.web.admin` sees health, pending players (one held during a stuck archive) and the audit log without positions; a player without it, and op alone, doesn't | `m7-dashboard-admin.mjs`; unit: core `AuditViewTest`, `WebTest` |
| Second-screen mode in WebKit at iPad landscape sizes: the widgets of `screens.second-screen` in their order, those the viewer may not see left out, dark, nothing scrolls; the online list and feed update while it sits (a join and a leave) with no reload; after Seasons Web restarts the stream reconnects; the wake lock is requested on load and again after the page is hidden and shown; without the API the page says so and works; an unknown widget name is a load warning | `m7-second-screen.mjs` (WebKit, a recording stand-in for `navigator.wakeLock` added before the page's script, and one run with none); unit: `ScreensTest`; JS: `wake.test.mjs`, `stream.test.mjs` |
| The page breaks no rule of the spec's security list: zero CSP violations across every route, requests only to its own origin, `textures.minecraft.net` and the frame origins, no image or texture file in the jar but our SVGs, assets with ETags and `304`s, nothing outside the asset list served | `m7-dashboard-csp.mjs`; unit: `AssetsTest` (including a scan of the jar's resources) |
| The connect page end to end: served by a local static server on its own origin, given `localhost:<tunnel port>`, it reads `seasons.json`, shows the server's name and lands on its dashboard, where login works | `m7-connect.mjs` (Chromium) |

### Public site tests (no Minecraft server, in `build.yml`'s site job)

| Behaviour | Test |
| --- | --- |
| Addresses: hosts, `host:port` and `https://` URLs accepted; `http://`, other schemes, user info, paths and junk refused with a reason | `site/test/address.test.mjs` (`node --test`) |
| `seasons.json`: only `service: seasons`, `api: v1` with a name is accepted; a `dashboard` on another origin is ignored | `site/test/instance.test.mjs` |
| The flow in Chromium and WebKit with stubbed answers: a Seasons instance goes to its dashboard; not https, not Seasons, no answer, a CORS refusal and a timeout each show their message and go nowhere; `?server=` connects | `site/test/connect.spec.mjs` (Playwright) |
| The site loads nothing from another origin, has its CSP meta, and every internal link resolves | `site/test/site.spec.mjs` |

Publishing itself is checked once after merge: the Pages run succeeds and the connect page answers on the site's address.

## Slices

Each slice is a few conventional commits; its scenarios pass before the next starts. Unit tests and the touched scenarios while working, the full suite once in the background before pushing.

1. **Readings and tooling.** Questions entries; ADR 0007 (Playwright) and ADR 0003's amendment for any scenario hooks; `lib/tunnel.mjs`, `lib/browser.mjs`, stubs; `:web:jsTest`; CI installs and caches the browsers.
2. **Seasons API 1.3 and the new endpoints.** `plannedHomecoming`, `seasons()`, `museum()`, `pending()`, `recentAudit()`; `/dashboard`, `/seasons`, `/museum`, `/admin/pending`, `/admin/audit`; `Screens`; `Framing`; `Assets` with a page shell. Unit tests; existing `m7-*` and A48's API half still pass.
3. **Live widgets and A48.** Clock, online, feed, leaderboards, map, links, converter; login on the page. `a48-public-dashboard` (widened), `m7-dashboard-live`, `-map`, `-login`, `-csp`, `-text`, `-visibility`.
4. **Profiles and reference.** `m7-dashboard-profile`, `-reference`.
5. **Admin widgets.** `m7-dashboard-admin`.
6. **Second-screen mode.** `m7-second-screen`.
7. **Public site.** `site/`, its tests, `pages.yml`, `m7-connect`.
8. **Docs.** `docs/progress.md` (M7 done), `docs/config.md` regenerated for `screens:` and the new visibility keys, `docs/paper-api.md` if anything was added, `README.md` (the dashboard and the connect page), `CHANGELOG.md` under `[Unreleased]`, `testkit/scenario-timings.json` for the new scenarios.

## Readings for `docs/questions.md`

1. **Which link is the map.** The first `links:` entry with `embed: frame`; the map widget shows it and the links widget leaves it out. No new setting.
2. **"A link if framing is blocked".** A browser can't tell a page's script that a frame was refused, so Seasons Web checks the map's own headers (`X-Frame-Options`, `frame-ancestors`) against `public-url` every 10 minutes, and the page falls back to the link after 10 s without a load as well. The link is always under the frame.
3. **Widgets the API had no data for.** The museum browser, season history, a planned Homecoming and the admin's audit log and pending players need Seasons API 1.3 (additive) and five endpoints beyond the spec's table, under `/api/v1`, each with a visibility key; the page's own settings come from `GET /api/v1/dashboard`.
4. **The audit log on the web.** "Never served: unpublished coordinates" holds for admins too: entries are shown by time, kind and names, without positions or free text that could hold them.
5. **"Current rules".** The zones in force with their modes in plain words and the Homecoming condition, plus any `links:` entry the owner labels as rules. No rules text setting.
6. **Homecoming countdown.** Shown only when `homecoming.unlock` has a date condition (the earliest); a vote-only Homecoming shows "when the group votes".
7. **Second-screen widgets.** The names in the spec's example (`season-clock`, `leaderboard`) and every other widget's own name are accepted; visibility still applies, so a second screen that isn't logged in leaves out player widgets; `layout: landscape` is the only layout; `/screen/<name>` shows other entries of `screens:`.
8. **The page's text is player-facing,** so it lives in Seasons Web's `messages.yml` under `dashboard.*` and reaches the page through `/dashboard`. The public site is not a plugin and keeps its text in its HTML.
9. **Items as text.** Names are the item id in words ("Diamond Sword") or the custom name, enchantments in words with roman levels; nothing from Minecraft's language files is bundled. Icons are ours, one per kind of item (weapons, tools, armour by slot, books, food, blocks, others).
10. **The connect page.** The address typed is the dashboard's (such as `seasons.petepeterepeat.com`), always over https; a `dashboard` in `seasons.json` is followed only on the same origin; nothing is stored in the browser; `?server=` links straight in.
11. **"The docs" on the public site.** Links to the repository's README and config reference; no copy of them is kept in `site/`.
12. **Wake lock.** Asked for on load and on every return to visible, and once more on the next tap after a refusal; a small status dot says whether it holds.

## Needs the owner

- Agreement to ADR 0007 (Playwright, test-only).
- The public site's address for `site/CNAME` and the Cloudflare record, and switching the repository's Pages source to GitHub Actions; until then the site publishes to the default `github.io` address.
