---
title: Cloudflare Tunnel
description: Publish your dashboard and map through a Cloudflare Tunnel when you can't open ports, with no port numbers for players.
art: server
audience: owners
since: 1.5.0
sidebar:
  order: 4
---

A Cloudflare Tunnel connects out from your server to Cloudflare, so nothing has to be opened on your router. It needs your domain's DNS on Cloudflare (free). If you can open ports 80 and 443, a [reverse proxy](/docs/hosting/reverse-proxy/) is simpler.

## 1. Install cloudflared

Follow Cloudflare's [install guide](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/) on the machine that runs Minecraft, then run it as a service.

## 2. Make the tunnel

In the Cloudflare dashboard: **Zero Trust → Networks → Tunnels → Create a tunnel**, choose **cloudflared**, and add two public hostnames:

| Public hostname | Service |
| --- | --- |
| `seasons.example.com` | `http://localhost:8019` |
| `map.example.com` | `http://localhost:8100` |

Cloudflare creates their DNS records and HTTPS certificates. Keep `play` as a plain A record, **DNS only** (grey cloud): Minecraft can't go through the tunnel.

## 3. Tell Seasons Web

```yaml
enabled: true
bind: 127.0.0.1:8019
public-url: https://seasons.example.com
proxy:
  trusted: [127.0.0.1]
  client-ip-header: CF-Connecting-IP
```

`cloudflared` runs on the same machine, so it connects from `127.0.0.1`, and Cloudflare adds the visitor's address in `CF-Connecting-IP`. Run `/web reload`.

## 4. The map

In BlueMap's `webserver.conf`: `ip: "127.0.0.1"`, `port: 8100`, and let the dashboard frame it:

```text
additional-headers: {
  "Content-Security-Policy": "frame-ancestors https://seasons.example.com"
}
```

BlueMap 5.24 and newer send keep-alives on their live stream, so Cloudflare doesn't close it. Seasons Web's own event stream does the same every 30 seconds.

## 5. Check it

Run [`/web check`](/docs/hosting/check-it/).
