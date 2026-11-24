# 11. Link preview images: Playwright for the site, Java2D for Seasons Web

Date: 2026-11-25 · Status: accepted

## Context

M9 gives every shared link a rich preview (Open Graph and Twitter cards) with its own 1200 × 630 image: each page of the public site and docs, and on each Seasons Web dashboard the server, each player, each Hall gallery and the museum. Social sites fetch the image and run no JavaScript, so the images must exist as PNG files: built ahead for the static site, drawn on demand by the dashboard, whose content changes as the season goes.

## Decision

- **The site:** after `astro build`, `site/scripts/og.mjs` opens one HTML card per page (`site/og/card.html`, styled with `design/tokens.css` and the fonts) in Playwright's Chromium, already a dev dependency (ADR 0007), and screenshots it to `dist/og/<page>.png`. No new dependency: the cards are the same HTML and CSS as the site.
- **Seasons Web:** draws its cards with Java2D (`java.awt.image.BufferedImage`, `javax.imageio.ImageIO`), part of the JDK, headless, off the server thread, with TrueType copies of the same three fonts in the jar (SIL OFL 1.1). Each image is cached by a hash of what it shows and served with an `ETag`.

## Consequences

- The Pages build installs Chromium (as the site job already does for tests).
- Seasons Web gains no library; the fonts add about 1 MB to its jar as TTF.
- Alternatives rejected: Satori and resvg (two new dependencies, a second layout engine with partial CSS); an image service (a third party sees every page shared); one static image for everything (no per-page or per-player preview).
