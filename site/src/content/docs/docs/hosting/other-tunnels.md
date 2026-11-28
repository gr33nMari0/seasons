---
title: Other tunnels
description: Use Tailscale Funnel or another tunnel in front of your dashboard, and what to check for each.
art: server
audience: owners
since: 1.5.0
sidebar:
  order: 5
---

Any tunnel or proxy works if it does these four things:

1. **HTTPS** at your `public-url`, with a real certificate.
2. **Passes long requests**: the dashboard's live updates are a stream kept open for hours, with a keep-alive every 30 seconds. Turn off response buffering if the tunnel has it.
3. **Adds the visitor's address in a header**, such as `X-Forwarded-For`, so per-visitor rate limits work. Put that header in `proxy.client-ip-header`, and the address the tunnel connects from in `proxy.trusted`.
4. **Connects to `127.0.0.1`**, so the dashboard isn't reachable any other way.

## Tailscale Funnel

[Tailscale Funnel](https://tailscale.com/kb/1223/funnel) publishes a local port at a `*.ts.net` address:

```text
tailscale funnel --bg 8019
```

Set `public-url` to the address it prints, `proxy.trusted: [127.0.0.1]` and `proxy.client-ip-header: X-Forwarded-For`. A `ts.net` address can't carry your own domain's records, so add your own domain with a CNAME if your plan allows, or share the address directly.

## Checking any tunnel

Run [`/web check`](/docs/hosting/check-it/). Its **Proxy** line says where requests arrive from and whether the header came with them, so you can tell at once if `proxy.trusted` or `client-ip-header` is wrong.
