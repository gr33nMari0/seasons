---
title: On a game-server host
description: Publish your dashboard and map when your server runs on a hosting panel, where you can't run your own proxy or tunnel.
art: server
audience: owners
since: 1.5.0
sidebar:
  order: 3
---

On a rented server with a control panel you usually can't install Caddy or cloudflared. You have three ways, best first.

## 1. Your host's own web proxy or subdomains

Many hosts can put a web address in front of a port for you: look in the panel for **subdomains**, **web proxy**, **reverse proxy** or **domains**. Point it at Seasons Web's port, and at BlueMap's, with HTTPS on. Then set:

```yaml
bind: 0.0.0.0:<the port the panel gave you>
public-url: https://seasons.example.com
proxy:
  trusted: [<the address the host's proxy connects from>]
  client-ip-header: X-Forwarded-For
```

Ask your host which address its proxy connects from, and which header it adds. Binding beyond `127.0.0.1` is allowed only with an https `public-url`, so the dashboard never serves plain HTTP to the internet.

## 2. Cloudflare in front of extra ports

If your domain is on Cloudflare, make `map` and `seasons` **proxied** (orange cloud) A records to your server's IP, and add an **Origin Rule** for each hostname that sends traffic to the port the panel allocated. Visitors still see no port. Set:

```yaml
bind: 0.0.0.0:<allocated port>
public-url: https://seasons.example.com
proxy:
  trusted: [173.245.48.0/20, 103.21.244.0/22, 103.22.200.0/22, 103.31.4.0/22, 141.101.64.0/18, 108.162.192.0/18, 190.93.240.0/20, 188.114.96.0/20, 197.234.240.0/22, 198.41.128.0/17, 162.158.0.0/15, 104.16.0.0/13, 104.24.0.0/14, 172.64.0.0/13, 131.0.72.0/22]
  client-ip-header: CF-Connecting-IP
```

The trusted ranges are [Cloudflare's published IP ranges](https://www.cloudflare.com/ips/); check that page for the current list.

## 3. Without a web address

If neither works, the dashboard can still run for you alone: leave `public-url` empty and `bind: 127.0.0.1:8019`, and open it on the machine itself. Players can't reach it this way.

Then run [`/web check`](/docs/hosting/check-it/).
