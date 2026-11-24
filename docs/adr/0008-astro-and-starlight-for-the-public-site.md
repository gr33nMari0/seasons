# 8. Astro and Starlight build the public site and its docs

Date: 2026-11-25 · Status: accepted

## Context

M9 (docs/plans/m9-design-overhaul.md) turns `seasonsmc.petepeterepeat.com` into the product site and the full public docs: install, guides, every command, permission and config key, searchable, in both themes, with "edit this page" links, and checked in CI so the docs can't drift from the code. Until now the site was two hand-written pages in `site/public`. The spec's list has no static site generator, and the docs are far too many pages to keep by hand.

## Decision

Build `site/` with **Astro** 7.4.8 and **Starlight** 0.42.10 (npm, pinned exactly, MIT), both build-time only:

- Starlight renders the docs (`site/src/content/docs/**`): sidebar, per-page contents, Pagefind search (built in, runs in the browser from static files), accessible markup and MDX components.
- The landing, download, connect and changelog pages are Astro pages in the same theme.
- Starlight's colours map onto `design/tokens.css`. Its ThemeProvider and ThemeSelect are replaced with `design/theme.js` and a two-state toggle, so the site, the docs and the dashboard follow the same theme rule (questions 335), and the site needs no inline script.
- GitHub Pages publishes `site/dist` from `pages.yml`; the CNAME and the `/connect/` path stay.

## Consequences

- The published site is still static files with a strict CSP and nothing loaded from another site; Pagefind needs `'wasm-unsafe-eval'` in `script-src`.
- `npm ci` in `site/` now installs Astro's toolchain (dev-only; nothing reaches a jar).
- Alternatives rejected: the GitHub Wiki (no review, no CI, drifts); MkDocs Material (a Python toolchain beside the repo's Node one); VitePress (no built-in docs IA as complete as Starlight's, Vue); a hand-built generator (search, navigation and accessibility to write ourselves).
