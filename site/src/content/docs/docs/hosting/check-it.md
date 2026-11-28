---
title: Check it
description: Run /web check to test web.yml and every part of how players reach your dashboard, and fix what it finds.
art: shield
audience: owners
since: 1.5.0
sidebar:
  order: 7
---

```text
/web check
```

Run it in game (with `seasons.web.admin.setup`, which ops have) or in the console. It checks `web.yml` without applying it, then tests how your dashboard is reached, and ends with a count. Nothing changes.

```text
Seasons Web check:
Pass · Settings: web.yml reads, and every setting in it is known.
Pass · Address: https://seasons.example.com answers as this dashboard.
Pass · Proxy: requests arrive from 127.0.0.1, which proxy.trusted lists, with CF-Connecting-IP.
Pass · Discovery: play.example.com: found, and https://seasons.example.com answers for it.
Pass · DNS: play.example.com points at play.example.com:25566 with its SRV record.
Pass · Map: https://map.example.com answers, and lets the dashboard frame it.
Check done: 6 passed, 0 warnings, 0 problems.
```

## What each line checks, and what to do

| Line | Checks | If it isn't a pass |
| --- | --- | --- |
| Settings | `web.yml` reads; no unknown settings (with the one you probably meant); none missing; nothing still `[fill me]` | Fix the typo it names. For missing settings, run `/web upgrade` |
| Address | The server fetches your `public-url` from outside, and the request reaches this very dashboard | Your proxy or tunnel isn't running, or points somewhere else. See [the addresses you need](/docs/hosting/records/) |
| Proxy | Where that request arrived from, and whether it carried `client-ip-header` | Put the address it names in `proxy.trusted`, or set `client-ip-header` to the header your proxy adds |
| Discovery | Each `game-addresses` entry has its `_seasons` TXT record, pointing here | See [let players find your dashboard](/docs/hosting/find-your-dashboard/) |
| DNS | Each game address has an A, AAAA or SRV record | Add the `play` record from [the addresses you need](/docs/hosting/records/) |
| Map | Your map answers and lets the dashboard frame it | Set BlueMap's `frame-ancestors` header, as in the [reverse proxy guide](/docs/hosting/reverse-proxy/) |

The look-ups go through the same public DNS-over-HTTPS resolvers the connect page uses, so the check sees what players' browsers will.

## Apply changes without a restart

After editing `web.yml`, run:

```text
/web reload
```

It applies `web.yml` and `messages.yml` at once and names the settings that changed. Logged-in devices stay logged in. A `web.yml` with a problem, such as a `bind` that isn't an address, isn't applied: the dashboard keeps running as it was, and the reply lists what's wrong.

## New settings after an upgrade

A new version of Seasons Web can add settings your `web.yml` doesn't have yet; they take their defaults until you add them, and the server log says so at startup. To see and add them:

```text
/web upgrade --dry-run
/web upgrade
```

The first lists what's missing. The second copies `web.yml` to `web.yml.bak-<version>`, then adds each missing setting with its default and the comment that explains it, at the end of its section. Every line you wrote stays as it was. Settings only you can fill, such as `public-url`, are written as `[fill me]`, which counts as unset until you change it. Then run `/web reload`. A section you wrote on one line, such as `proxy: {trusted: []}`, is left for you to add by hand.
