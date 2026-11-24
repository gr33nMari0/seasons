# 10. Link and accessibility checks for the public site

Date: 2026-11-25 · Status: accepted

## Context

M9's docs are meant to stay right: every link working, every page usable with a keyboard and a screen reader, in both themes and at phone width. Playwright (ADR 0007) already drives the site in tests, but nothing checks links between docs pages at build time or audits accessibility.

## Decision

Add, dev-only and pinned exactly:

- **`starlight-links-validator`** 0.27.0 (MIT): the build fails on a broken link or anchor between docs pages.
- **`@axe-core/playwright`** 4.14.0 (MPL-2.0): the browser tests run axe on every page in both themes at 375 and 1280 px, and fail on any serious or critical violation.

## Consequences

- Nothing ships: both run only in `npm run build` and `npm test`.
- axe catches what can be checked by machine (contrast, names, landmarks, roles); reading the pages is still needed.
