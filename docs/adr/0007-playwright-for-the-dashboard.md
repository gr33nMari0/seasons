# 7. Playwright drives the dashboard and the connect page in tests

Date: 2026-11-23 · Status: accepted

## Context

M7 part 2 adds two web pages: Seasons Web's dashboard and the public site's connect page. A48 says the dashboard *shows* the season clock, who's online, the event feed, the map and the Hall, and second-screen mode is mostly about how a browser behaves (the Screen Wake Lock API, a landscape iPad, auto-refresh). Part 1's scenarios check the API with Node's `fetch`, which runs none of the page's script and applies none of its content security policy. No browser is in the spec's dependency list.

## Decision

Add `playwright` 1.65.0 (npm, pinned exactly, Apache-2.0), test-only, to `testkit/bots/package.json` and `site/package.json`, with two of its browsers:

- **Chromium**, for most dashboard scenarios and the connect page.
- **WebKit**, the engine of every iPad browser, for second-screen mode, the login and the connect page.

Scenarios open the dashboard through `testkit/bots/lib/tunnel.mjs`, an HTTPS reverse proxy written with Node's own `https` and `http` modules and the scenario suite's existing self-signed certificate, which adds `CF-Connecting-IP` as Cloudflare's tunnel does. Browsers then see an `https://localhost` origin, so `__Host-` cookies, the wake lock's secure context, HSTS and the CSP behave as in production, and the event stream streams. `testkit/bots/lib/browser.mjs` records every request's origin and every CSP violation and fails a scenario on either; requests to `open.spotify.com` and `textures.minecraft.net` are answered locally, so CI needs no outside network.

The page's and the site's pure modules (formatting, the coordinate converter, the address checks, the wake-lock and stream logic) are tested with Node's built-in test runner (`node --test`), which needs nothing installed.

## Consequences

- Nothing ships: Playwright is in no jar and on no site page.
- CI installs Chromium and WebKit with their system libraries (`npx playwright install --with-deps chromium webkit`, about 250 MB) in the scenario shards and the site job, cached by Playwright version. `./gradlew scenarioTest` installs them locally (`:testkit:installBrowsers`).
- Playwright's WebKit is not Safari on iPadOS, but it is the same engine; the wake lock is checked through a recording stand-in for `navigator.wakeLock` (real WebKit refuses it without a screen), and once without the API at all, as older iPads have.
- Alternatives rejected: checking the served HTML as text (runs no script, applies no CSP, can't see the feed arrive); jsdom (not a browser engine, no CSP, no wake lock, no `EventSource`); Selenium with drivers per browser (more moving parts, no WebKit on Linux).
