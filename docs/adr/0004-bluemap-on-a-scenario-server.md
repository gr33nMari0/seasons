# 4. BlueMap on the scenario servers that ask for it

Date: 2026-11-19 · Status: accepted

## Context

M3 part 3 publishes a `Seasons` marker set to BlueMap through its API, and publishes it again when BlueMap reloads, which drops every marker added through the API. Tests against a fake API can check what Seasons hands BlueMap, but not that the calls link against the API a real BlueMap bundles, that `BlueMapAPI#getWorld` finds Paper 26.3's worlds, or that a real `/bluemap reload` brings the markers back. Seasons must also run without BlueMap, which every other scenario server shows by not having it.

## Decision

- A scenario file can ask for optional plugins with a header line, `// @plugins bluemap`. That scenario gets a fresh server of its own, as `// @isolated` does, with the plugin installed as well; no other server has it. `testkit/build.gradle.kts` lists the optional plugins (`optionalPlugins`); each is downloaded through run-paper's download service, as ViaVersion is (ADR 0001), and only when a selected scenario asks for it. BlueMap is pinned in `gradle/libs.versions.toml` as `bluemap-plugin`, for test servers only: it never ships, and `runServer` doesn't install it.
- The shard writes BlueMap's settings before that boot and removes them after: `accept-download: true`, since BlueMap won't start its API without the game's client jar, which it downloads from Mojang; its data folder (where that jar goes) in `testkit/build/scenario/<version>/plugin-data/bluemap`, kept between local runs; no web server or web app, which the scenarios don't use and whose port would clash between shards; no metrics; no skin downloads; one render thread.
- The test plugin gains `/scenario bluemap`, which prints every marker in a marker set whose id starts with `seasons`, on every map of every world, through BlueMap's own API, and the identity of BlueMap's API instance, which tells one start of the API from the next. Like the stand-ins in ADR 0003, it reads BlueMap and is no hook into Seasons. The test plugin declares BlueMap as an optional dependency and touches its classes only when it is installed.

## Consequences

- `m3-bluemap` checks the marker set on a real BlueMap, its reload included. Every other scenario runs without BlueMap, so the suite as a whole shows Seasons runs without it.
- That scenario downloads the game's client jar from Mojang the first time it runs on a machine (each CI shard job that gets it), so it needs network access to Mojang as well as to Modrinth.
- A BlueMap update is a version bump in `libs.versions.toml`; `m3-bluemap` fails if the API it bundles lacks a call Seasons makes.
