---
title: The addresses you need
description: The DNS records that put your game, your map and your Seasons Web dashboard on addresses players can type, with no port numbers.
art: map
audience: owners
since: 1.5.0
sidebar:
  order: 1
---

Players should type one address to join and open two web pages, with no port numbers anywhere. These records get you there. Add them at your DNS host, for your own domain (here `example.com`, with `203.0.113.10` standing in for your server's public IP address).

| Name | Type | Value |
| --- | --- | --- |
| `play` | A | `203.0.113.10` |
| `_minecraft._tcp.play` | SRV | `0 5 25565 play` |
| `map` | A or CNAME | `203.0.113.10` |
| `seasons` | A or CNAME | `203.0.113.10` |
| `_seasons.play` | TXT | see below |

- **`play`** is the address players join with. Add an AAAA record too if you have IPv6. Never proxy it: web proxies can't carry Minecraft.
- **`_minecraft._tcp.play`** (SRV) lets players leave the port off. It's needed when your port isn't 25565 and harmless when it is. Its target is `play.example.com`.
- **`map`** is your BlueMap and **`seasons`** your Seasons Web dashboard. Each is an A record to your IP, or a CNAME to your tunnel.
- **`_seasons.play`** (TXT) lets players find the dashboard from the address they play on. Its value is `v=seasons1; url=https://seasons.example.com`.

## How it fits together

```text
play.example.com     ──▶  Minecraft, port 25565
map.example.com      ──▶  HTTPS (proxy or tunnel)  ──▶  BlueMap, 127.0.0.1:8100
seasons.example.com  ──▶  HTTPS (proxy or tunnel)  ──▶  Seasons Web, 127.0.0.1:8019
```

Minecraft's port is the only one players reach directly. The map and the dashboard listen only on `127.0.0.1`, and something in front of them adds HTTPS. Choose one:

1. **[A reverse proxy on your own machine](/docs/hosting/reverse-proxy/)**. Recommended: no account anywhere, free certificates, a few lines of config. You need ports 80 and 443 open.
2. **[On a game-server host](/docs/hosting/game-panel/)**, when you rent a server through a panel and can't run your own proxy.
3. **[Cloudflare Tunnel](/docs/hosting/cloudflare-tunnel/)**, when you can't open ports, such as at home behind a router you don't control.
4. **[Other tunnels](/docs/hosting/other-tunnels/)**, such as Tailscale Funnel.

Then [let players find your dashboard](/docs/hosting/find-your-dashboard/) and [check it all](/docs/hosting/check-it/).

## The game address

Add `play` as an **A** record to your server's IP. If your DNS host offers a proxy (Cloudflare's orange cloud), turn it **off** for `play`: Minecraft traffic can't go through a web proxy.

Add the **SRV** record when your port isn't 25565, such as on a host that gave you 25566:

```text
_minecraft._tcp.play.example.com.  SRV  0 5 25566 play.example.com.
```

Players then type just `play.example.com`. Only the port in the SRV record changes from host to host.

:::tip
If your IP address changes (a home connection), use your router's or DNS host's dynamic DNS so the A record follows it.
:::
