---
title: A reverse proxy on your machine
description: Serve your dashboard and map over HTTPS with Caddy or nginx on the same machine, with free certificates and no third-party account.
art: server
audience: owners
since: 1.5.0
sidebar:
  order: 2
---

The simplest way to put your dashboard and map on HTTPS: a web server on the same machine that gets certificates by itself and passes requests to Seasons Web and BlueMap on `127.0.0.1`. Nothing else is needed: no tunnel, no account.

**You need:** a machine you control (a VPS or a home server), ports **80** and **443** reachable from the internet, and the [records](/docs/hosting/records/) `map` and `seasons` as A records to its IP.

## 1. Keep the map and the dashboard on localhost

In `plugins/SeasonsWeb/web.yml`:

```yaml
enabled: true
bind: 127.0.0.1:8019
public-url: https://seasons.example.com
proxy:
  trusted: [127.0.0.1]
  client-ip-header: X-Forwarded-For
```

In BlueMap's `webserver.conf`, set `ip: "127.0.0.1"` and `port: 8100`. Then run `/web reload`.

## 2a. Caddy (recommended)

Install [Caddy](https://caddyserver.com/docs/install), then put this in `/etc/caddy/Caddyfile`:

```text
seasons.example.com {
	reverse_proxy 127.0.0.1:8019
}

map.example.com {
	reverse_proxy 127.0.0.1:8100
}
```

Reload Caddy (`sudo systemctl reload caddy`). It gets and renews certificates by itself, and sends `X-Forwarded-For`. The dashboard's live updates work through it with no extra settings.

## 2b. nginx

With nginx, get certificates with [certbot](https://certbot.eff.org/) (`sudo certbot --nginx -d seasons.example.com -d map.example.com`), then for each site:

```nginx
server {
    server_name seasons.example.com;
    listen 443 ssl;
    # certbot fills in the ssl_certificate lines

    location / {
        proxy_pass http://127.0.0.1:8019;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        # The live event stream: don't buffer it, and don't time it out.
        proxy_buffering off;
        proxy_read_timeout 1h;
    }
}
```

The map's block is the same with `map.example.com` and port `8100`.

## 3. Let the dashboard show the map

BlueMap must allow the dashboard to frame it. In BlueMap's `webserver.conf`:

```text
additional-headers: {
  "Content-Security-Policy": "frame-ancestors https://seasons.example.com"
}
```

## 4. Open the ports

Forward TCP 80 and 443 on your router to the machine, and allow them in its firewall. If your internet provider blocks them (some home connections do), use [Cloudflare Tunnel](/docs/hosting/cloudflare-tunnel/) instead.

## 5. Check it

Run `/web check`. It fetches your `public-url` from outside, confirms it reaches this dashboard, and says whether the proxy's header arrives. See [Check it](/docs/hosting/check-it/).
