# Contributing to Seasons

Thanks for helping. Please read this before opening a pull request.

## Licence grant

Seasons is source-available under the PolyForm Noncommercial License 1.0.0, and Peter Woodhead keeps the copyright so he can grant commercial licences. **By submitting a contribution (code, documentation or anything else) you agree that Peter Woodhead may use, change, relicense and sublicense it on any terms, including commercial terms**, and you confirm you have the right to grant that. Contributions are accepted only on that basis.

## Building

You need JDK 25 (Temurin) and Node 24. On macOS: `brew install --cask temurin@25 && brew install node`. On Nix: `nix develop`.

```bash
./gradlew build                       # compile, unit tests, both jars
./gradlew :adapters:paper:runServer   # Paper 26.3 in adapters/paper/run/ with both plugins installed
./gradlew scenarioTest                # Paper servers plus Mineflayer bots run the acceptance scenarios
./gradlew scenarioTest -Pscenarios=a33-homecoming,m3-*   # just those scenarios
./gradlew :core:configReference       # regenerate config.yml's comments and docs/config.md
```

The jars land in `adapters/paper/build/libs/Seasons-<version>.jar` and `web/build/libs/SeasonsWeb-<version>.jar`. `scenarioTest` runs the scenarios in shards side by side, each shard on one Paper server that is reset between scenarios; `-PscenarioIsolation=fresh` gives every scenario a server of its own instead. Server logs and results land in `testkit/build/scenario-results/`, timings in `build/reports/scenario-timings.md`. The dev server and the scenario servers never report to bStats: both turn it off before every boot.

```
api/                 Seasons API (seasons-api): public, platform-neutral
core/                all season logic; plain Java, no Minecraft imports; owns the ports
adapters/paper/      the Paper Seasons Adapter: implements the ports with public Paper API
integrations/        BlueMap and Discord, each enabled only when present
web/                 the Seasons Web plugin
testkit/             the test plugin and the Mineflayer scenario suite (testkit/bots)
buildSrc/            the build logic that runs scenario shards
docs/                spec, config reference, paper-api.md, questions.md, adr/
```

The full design is in [docs/spec.md](docs/spec.md).

## How to work

- `docs/spec.md` is the source of truth. If something is ambiguous, note it in `docs/questions.md`.
- Branch from `main`, keep commits small with [conventional messages](https://www.conventionalcommits.org/), and open a pull request. The `build` check must pass.
- While working, run the unit tests and the scenarios you touch: `./gradlew build` and `./gradlew scenarioTest -Pscenarios=a33-homecoming,m3-*` (one scenario takes well under a minute). Run the full suite, `./gradlew build scenarioTest`, once before pushing; it runs in shards side by side and takes a few minutes. `./gradlew spotlessApply` fixes formatting and adds the licence header.
- Scenarios share a server, reset between them; a reset that leaves something behind fails the scenario before it. Don't sleep in a scenario: move Seasons' clock with `ctx.advance`, end quiet windows with `ctx.quiet` and `ctx.sync`, and wait game ticks with `ctx.sync(admin, bots, ticks)` (ADR 0003). A scenario the reset truly can't serve gets `// @isolated <reason>`. The order is shuffled; a failure prints the seed to repeat it with `-PscenarioSeed=<n>`, and `-PscenarioIsolation=fresh` runs every scenario on a server of its own. After adding scenarios, refresh `testkit/scenario-timings.json` with `./gradlew :testkit:updateScenarioTimings` after a full run.
- Every source file starts with `// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0`.
- Public Paper API only, and list every Paper API you call in `docs/paper-api.md`.
- Player-facing text goes in `messages.yml`. Settings are Configurate records in `core/.../config/SeasonsConfig.java`, each component described by a `@Comment`; after changing one, run `./gradlew :core:configReference`, which writes the bundled `config.yml` and `docs/config.md` from them.
- Add a changelog entry for every behaviour change.
- No new dependency without a short record in `docs/adr/`. After any dependency change (Dependabot pull requests included), refresh `gradle/verification-metadata.xml` with `./gradlew --write-verification-metadata sha256 build scenarioTest` and commit it. Run it with an empty Gradle cache (`GRADLE_USER_HOME=$(mktemp -d)`) so it records everything CI will download.

## Releasing

Set `version` in `gradle.properties` (for example `1.0.0`, or `1.1.0-beta.1` for a beta), move the changelog's Unreleased entries under that version, merge to `main` and wait for the build to pass. Then `git tag v1.0.0 && git push origin v1.0.0`. `release.yml` attaches both jars and `SHA256SUMS` to a GitHub Release, then publishes the Seasons jar to Hangar and Modrinth with that version's changelog section (ADR 0006). A version with a hyphen is a pre-release on GitHub, the `Beta` channel on Hangar and a beta on Modrinth. The Hangar project (`hangarProject` in `gradle.properties`, with a `Beta` channel) and the Modrinth project (`modrinthProject`) must exist, and the repository needs the secrets `HANGAR_API_TOKEN` and `MODRINTH_TOKEN`. A failed store upload can be re-run on its own from the workflow run. Afterwards set the version to the next `-SNAPSHOT`.

## Reporting dupes or exploits

Never in a public issue. See [SECURITY.md](SECURITY.md).
