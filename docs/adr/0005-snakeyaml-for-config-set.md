# 5. SnakeYAML for editing config.yml in place

Date: 2026-11-19 · Status: accepted

## Context

`/season config set <path> <value>` writes the value back to `config.yml` (questions 54). Unlike the season, gallery and marker files, which hold data only, `config.yml` is a human-owned file whose comments are its documentation, and a server's copy may carry the admin's own notes. Configurate 4.2.0's YAML loader doesn't read comments, so loading `config.yml` and saving it again through Configurate would wipe all of them. Configurate does bundle SnakeYAML, but under its own `internal` package, which isn't API to build on.

## Decision

Add `org.yaml:snakeyaml` 2.7 (the release Paper 26.3 bundles) as a dependency of the core, shaded and relocated under `com.petepeterepeat.seasons.lib` like Configurate, and pinned by Gradle dependency verification. `config/YamlEdits` uses its composer, which gives every node's start and end in the file, to change only the characters of one value, or to add one missing key under its section, and its emitter to write the value in flow style, quoted only where needed. Everything else in the file, comments and blank lines included, stays byte for byte. YAML anchors, aliases, merge keys and files of several documents are refused with a request to edit by hand. Reading config files still goes through Configurate.

## Consequences

- A command edit and a hand edit of `config.yml` never fight: the command changes one value and leaves the rest as the admin wrote it.
- The jar grows by about 330 KB.
- Alternatives rejected: Configurate's internal copy of SnakeYAML (a package Configurate may change without notice); rewriting `config.yml` from the bundled template (it would replace the admin's notes and fill a trimmed file with every key); a hand-written YAML locator (new parsing code for a format with many edge cases).
- The relocation keeps Seasons' SnakeYAML apart from the copy Paper bundles.
