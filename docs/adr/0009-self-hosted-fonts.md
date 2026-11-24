# 9. Three self-hosted typefaces

Date: 2026-11-25 · Status: accepted

## Context

The Seasons design (the Claude Design export and the Figma assets) uses Pixelify Sans for headings, Inter for text and JetBrains Mono for commands. The site and the dashboard so far used system fonts only, because their content security policies allow nothing from another site and a dashboard must not tell Google who looks at it.

## Decision

Ship the three typefaces as files in `design/fonts/` (latin subsets from Fontsource 5.3.0, woff2, SIL Open Font License 1.1, licences beside them), declared in `design/fonts.css`:

- Pixelify Sans 400, 600, 700; Inter 400, 500, 600, 700, 800; JetBrains Mono 400, 600 (about 200 KB in all).
- The site copies them into its build; Seasons Web bundles them in its jar and serves them from `/app/fonts/`. Nothing is fetched from a font service.
- Every stack falls back to system fonts, and `font-display: swap` shows text at once.

## Consequences

- The Seasons Web jar grows by about 200 KB.
- The fonts are listed in `THIRD_PARTY_NOTICES.md`; the OFL allows bundling and redistribution with the licence.
- Preview images drawn by Seasons Web (ADR 0011) use TrueType files of the same faces, since Java2D can't read woff2.
