# 2. configurate-gson for JSON data files

Date: 2026-11-18 · Status: accepted

## Context

The spec stores plugin-owned data as JSON: the season state (`data/season.json`) now, and player records from M1 part 2 on. Each file carries a `format-version` and migrates forward on load. The spec's dependency list has Configurate for YAML but no JSON library.

## Decision

Add `org.spongepowered:configurate-gson`, from the same Configurate release as `configurate-yaml`. It brings Gson, which is small and widely used. Both are shaded and relocated under `com.petepeterepeat.seasons.lib`, like the rest of Configurate, and pinned by Gradle dependency verification.

## Consequences

- JSON and YAML files go through the same node model, the same object mapping and the same versioned migrations.
- Alternatives rejected: Jackson is much larger and has its own mapping rules; a hand-written JSON reader would be new code in exactly the files crash recovery depends on.
- The relocation keeps Seasons' Gson apart from the copy Paper bundles.
