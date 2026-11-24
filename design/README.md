# Seasons design

One design system for the public site (`site/`), its docs and the Seasons Web dashboard (`web/`), from the Seasons art: the hourglass logo, the Hall's arches and carpet, and the grass and dirt strips (`docs/plans/m9-design-overhaul.md`).

| File | What it is |
| --- | --- |
| `tokens.css` | The palette and the semantic tokens (`--bg`, `--surface`, `--text`, `--accent`, …) for dark (default) and parchment light |
| `fonts.css`, `fonts/` | Pixelify Sans, Inter and JetBrains Mono, self-hosted woff2 with their licences (ADR 0009) |
| `theme.js` | The theme rule: follow the system, a toggle until the system changes, no flash (questions 335) |
| `icons.svg` | Pixel UI icons, 16 × 16 symbols in the text's colour, drawn by `tools/icons.mjs` |
| `art/` | Pixel art: the logo (traced from `docs/images/icon.png`), trophy, figures in each armour, chest, egg, house, tree, ground strips, heads, maps |

The site copies this folder into its build and Seasons Web bundles it in its jar (`/app/design/`), so neither keeps its own copy. Change a token here and both follow.

## Rules

- Square corners, 2 px borders, hard pixel shadows (`--shadow-card`, `--shadow-chip`).
- Pixelify Sans for headings and the brand only; Inter for reading; JetBrains Mono for anything typed in game.
- Gold is the accent. In light mode, gold text uses `--accent-text`, which is darker so it reads on parchment.
- Pixel art keeps `shape-rendering: crispEdges` and is scaled by whole numbers where it can be.
- Every text colour pair passes WCAG AA in both themes; `site/test/design.test.mjs` checks them.
- No inline styles: the dashboard's content security policy forbids them.
