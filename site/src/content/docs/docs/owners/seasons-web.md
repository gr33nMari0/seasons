---
title: Seasons Web
description: "The optional live dashboard served from your own server: install, web.yml, putting it behind a tunnel or proxy, visibility, the second screen and link previews."
art: map
audience: owners
since: 1.2.0
sidebar:
  order: 15
---

Seasons Web is a second, optional plugin that turns your server into a live dashboard for phones, tablets and second monitors: the season clock, who's online and where (never coordinates), the event feed, your map, leaderboards, profiles, the Hall of Fame and the museum, and a second-screen mode for a tablet on a stand. Players lay out their own dashboards, too.

It's **off by default** and **read-only**: admin actions stay in game. It runs on your server, behind your own address; the public site only helps people find it.

## Install

1. Put `SeasonsWeb-<version>.jar` beside `Seasons-<version>.jar` in `plugins/` and restart. It needs Seasons and is released with it.
2. In `plugins/SeasonsWeb/web.yml`, set:

   ```yaml
   enabled: true
   bind: 127.0.0.1:8019                     # change the port if 8019 is taken
   public-url: https://seasons.example.com  # the https address players open
   game-addresses: [play.example.com]       # the address players join with
   name: Example SMP                        # shown on the dashboard and in previews
   ```

3. Put a **Cloudflare Tunnel** or an **HTTPS reverse proxy** in front of `127.0.0.1:8019`, at your `public-url`. HTTPS comes from there. Binding beyond localhost without an https `public-url` stops the dashboard from starting.
4. Tell it about the proxy, so rate limits see visitors' real addresses:

   ```yaml
   proxy:
     trusted: [127.0.0.1]               # where the tunnel or proxy connects from
     client-ip-header: CF-Connecting-IP # Cloudflare's; X-Forwarded-For for most proxies
   ```

5. Restart, then open your `public-url`. `web.yml` changes take effect at the next restart.
6. Publish the discovery record so players can find the dashboard from the address they play on: see [Let players find your dashboard](/docs/guides/publish-dashboard/).

## Logging in

The dashboard shows a one-time code, such as `K7QX-2M`; the player types `/web link K7QX-2M` in game, and that device is logged in as them for 30 days (`sessions.lifetime`). No passwords. `/web sessions` lists their devices and `/web logout [device|all]` ends them. Codes are single-use, expire after 5 minutes and are rate-limited.

## Who sees what

| Level | Who | Sees by default |
| --- | --- | --- |
| Public | Anyone with the address, when `public: true` | The season clock, who's online and the area they're in, the event feed, the map, the Hall and museum, history, waypoints, links |
| Player | Logged in with `/web link` | Everything public, plus leaderboards, full profiles and archived gear as text |
| Admin | Players with `seasons.web.admin` (nobody by default) | Server health, pending players, the audit log |

Every widget and field has a visibility setting: `public`, `players`, `admins` or `hidden`.

```yaml
visibility:
  online.region: players
  leaderboards: players
  hall.gear: players
  server-health: admins
```

Players can hide themselves from leaderboards with `/web privacy`.

## Links and the map

```yaml
links:
  - { label: Discord, url: "https://discord.gg/[INVITE]" }
  - { label: Server playlist, url: "https://open.spotify.com/playlist/[ID]", embed: spotify }
  - { label: Map, url: "https://map.example.com", embed: frame }
```

The first link with `embed: frame` is the map widget. If your map refuses to be framed, the widget links to it instead.

## Help for your players

The dashboard has a **Help** page and widget with the player guides from these docs, bundled in the plugin so they match your version. Add your own server's rules and links there:

```yaml
help:
  links:
    - { label: Server rules, url: "https://example.com/rules" }
  rules: rules.md   # a Markdown file in plugins/SeasonsWeb/
```

## The second screen

`/screen` is a landscape layout for a tablet on a stand or a spare monitor: large type, always dark, kept up to date and kept awake. Its widgets and their order come from `web.yml`:

```yaml
screens:
  second-screen:
    layout: landscape
    widgets: [season-clock, online, leaderboard, event-feed, map, links]
```

Add more screens under other names and open them at `/screen/<name>`. An easier way to design one: arrange a dashboard in the browser, then use **Copy as web.yml** in its edit menu.

## Custom dashboards

Every viewer can make their own dashboards: drag and resize widgets, add and remove them, and keep several. Layouts are kept in that browser. See [The dashboard](/docs/players/dashboard/) in the players' guide. Visibility still applies: a layout can only show what that viewer is allowed to see.

## Link previews

Shared dashboard links unfurl with a title, a description and a picture drawn by Seasons Web: the server's season, phase and who's online; a player's head, seasons and awards; a gallery's figures; the museum's eggs. Previews only ever show what an anonymous visitor may see: with `profiles: players` (the default) a player's preview names the server and nothing about them, and players hidden with `/web privacy` never get a personal card.

```yaml
previews:
  enabled: true
  skins: true        # draw players' heads from their skins; false uses a plain figure
```

## Rate limits and caching

The defaults suit a small community; raise them for a big one:

```yaml
rate-limits:
  requests-per-minute-per-ip: 120
  requests-per-minute-per-account: 240
cache:
  public-max-age: 5s   # how long Cloudflare may cache a public answer
```
