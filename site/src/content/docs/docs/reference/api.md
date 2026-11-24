---
title: API
description: seasons-api for other plugins, and the read-only Web API Seasons Web serves.
art: server
audience: owners
sidebar:
  order: 4
---

## seasons-api, for plugins

Seasons publishes read-only snapshots and events through `seasons-api`, which follows semantic versioning: a change that breaks a plugin using it needs a new major version. Seasons Web is built on it.

Get it from Bukkit's services manager once Seasons has enabled:

```java
RegisteredServiceProvider<SeasonsApi> reg =
    getServer().getServicesManager().getRegistration(SeasonsApi.class);
SeasonsApi seasons = reg.getProvider();
```

Declare Seasons as a dependency in your `paper-plugin.yml`, and check `seasons.apiVersion()` against the range you support.

**Queries:** `version()`, `apiVersion()`, `currentSeason()`, `season()`, `online()`, `isOnline(uuid)`, `profile(uuid)`, `gallery(season)`, `archive(season, uuid)`, `leaderboards(season)`, `waypoints()`, `seasons()`, `museum()`, `pending()`, `audit(count)`. Those that read files return a `CompletableFuture`.

**Events** (`subscribe(listener)`): `PlayerJoined`, `SeasonFirst`, `DragonSlain`, `ZoneEntered`, `PhaseChanged`, `HomecomingStarted`, `PlayerArchived`.

## The Web API

Seasons Web serves a read-only JSON API under `/api/v1`, versioned, on your dashboard's address. Each endpoint answers by its [visibility](/docs/owners/seasons-web/#who-sees-what) for the viewer.

| Endpoint | Access | Returns |
| --- | --- | --- |
| `GET /.well-known/seasons.json` | Public | The instance: name, versions, features, login methods, game addresses |
| `POST /api/v1/auth/device`, `GET /api/v1/auth/poll` | Public, rate-limited | A device code; the session once linked |
| `GET /api/v1/season` | Public | Phase, stage, dates, countdowns |
| `GET /api/v1/seasons` | By visibility | Season history |
| `GET /api/v1/players/online` | By visibility | Who's online, their dimension and area, never coordinates |
| `GET /api/v1/players/{uuid}` | Own profile, plus public fields of others | Profile, seasons, cards, awards |
| `GET /api/v1/hall/{season}`, `/hall/{season}/{uuid}` | By visibility | A gallery, and archive contents as text |
| `GET /api/v1/museum` | By visibility | The museum |
| `GET /api/v1/leaderboards?season=` | By visibility | Rankings |
| `GET /api/v1/waypoints`, `/api/v1/links` | By visibility | Shared waypoints and links |
| `GET /api/v1/events` | By visibility | A server-sent event stream |
| `GET /api/v1/dashboard` | Public | The page's settings and text |
| `GET /api/v1/admin/health`, `/admin/pending`, `/admin/audit` | Admin | Tick time, TPS, memory; pending players; recent audit entries |

Sessions are an HttpOnly, Secure, SameSite=Strict cookie, so tokens never reach JavaScript.
