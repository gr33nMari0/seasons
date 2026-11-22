# 6. Publishing to Hangar and Modrinth with their Gradle plugins

Date: 2026-11-22 · Status: accepted

## Context

The spec has tagged releases published to Hangar, Modrinth and GitHub Releases from GitHub Actions, with Hangar and Modrinth joining `release.yml` at M6 and their API tokens kept as repository secrets. `release.yml` already builds both jars, writes `SHA256SUMS` and makes the GitHub Release; a tag with a hyphen, such as `v1.1.0-beta.1`, is a pre-release there. Neither store's upload is in the spec's dependency list.

## Decision

Add two Gradle plugins, build tooling only, pinned in `libs.versions.toml` and by Gradle dependency verification:

- `io.papermc.hangar-publish-plugin` 0.1.4, Paper's own plugin for Hangar's API (task `publishAllPublicationsToHangar`).
- `com.modrinth.minotaur` 2.10.0, Modrinth's own Gradle plugin (task `modrinth`).

Both are applied in `adapters/paper` and configured from `gradle.properties` (`hangarProject`, `modrinthProject`, `minecraftVersions`) and `CHANGELOG.md` (the version's section is the changelog). A version with a hyphen goes to Hangar's `Beta` channel and is a Modrinth `beta`; any other is `Release` and `release`. Modrinth lists the `paper` and `purpur` loaders, and both list BlueMap as an optional dependency.

In `release.yml` the existing job uploads its `dist/` folder, and two jobs, `hangar` and `modrinth`, each publish the Seasons jar from it (`-PreleaseJar`), so all three places carry the same bytes as `SHA256SUMS`, and a failed store upload can be re-run alone. Each job sees only its own secret (`HANGAR_API_TOKEN`, `MODRINTH_TOKEN`), set on the one step that uploads; only the GitHub Release job has write access to the repository.

## Consequences

- Nothing ships: neither plugin is on any runtime classpath.
- The Hangar project needs a `Beta` channel (new projects have `Release` and `Snapshot`), and both store projects must exist before the first tag (docs/questions.md 292).
- Seasons Web is not listed on either store until M7 gives it something to do; its jar stays on the GitHub Release.
- Alternatives rejected: calling each store's HTTP API with `curl` from the workflow (multipart uploads, JSON payloads and error handling written by hand, for what the stores' own plugins already do); third-party GitHub Actions for the uploads (another publisher holding both tokens).
