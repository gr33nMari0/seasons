---
title: Let players find your dashboard
description: Publish a DNS record so players can type the address they play on and land on your Seasons Web dashboard.
art: map
audience: owners
since: 1.4.0
sidebar:
  order: 6
---

Players shouldn't need to know your dashboard's address. On the [connect page](/connect/) they type the address they **join the game with**, such as `play.example.com`, and are taken to your dashboard. For that, publish one DNS record.

## 1. Tell Seasons Web your game address

In `plugins/SeasonsWeb/web.yml`:

```yaml
public-url: https://seasons.example.com
game-addresses: [play.example.com, mc.example.com]
```

List every address players join with, then run `/web reload`.

## 2. Publish the record

Run `/web discovery` in game or in the console. It prints the record for your addresses, such as:

```text
_seasons.play.example.com.  TXT  "v=seasons1; url=https://seasons.example.com"
```

Add it at your DNS host. In Cloudflare: **DNS → Records → Add record**, type **TXT**, name `_seasons.play`, content `v=seasons1; url=https://seasons.example.com`. Add one per game address.

If your game address is an SRV record (`_minecraft._tcp.example.com`), put the TXT record on the name players type, `_seasons.example.com`.

## 3. Check it

```text
/web discovery check
```

It looks the record up, checks it points at your `public-url`, and checks the dashboard answers. [`/web check`](/docs/hosting/check-it/) does the same as part of checking everything else. Then try the [connect page](/connect/) yourself with your game address.

## How it's found

The connect page looks for the `_seasons` TXT record through public DNS-over-HTTPS resolvers, then confirms with your dashboard itself: its `/.well-known/seasons.json` must list the address the player typed in `gameAddresses`. Without a record, it also tries `seasons.<your domain>` and asks the same question, so a guess can never send a player to someone else's dashboard.

Share a direct link in Discord: `https://seasonsmc.petepeterepeat.com/connect/?server=play.example.com` finds your dashboard as soon as it opens.
