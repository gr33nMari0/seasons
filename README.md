# Seasons

A server-side Paper plugin that runs a season format on one persistent Minecraft world. At season end every player's gear is archived into a Hall of Fame, everyone restarts at a fresh spawn far out, and the old world stays locked until the group votes for Homecoming. Players install nothing.

- **Seasons**: the plugin (`adapters/paper` packages it with `core`, `api` and `integrations`).
- **Seasons Web**: an optional read-only dashboard plugin (`web`).
- **Seasons API**: the public API other plugins build on (`api`).

The full design is in [docs/spec.md](docs/spec.md). Status: **M0 skeleton** — the build, ports and test harness exist; no season features yet.

## Requirements

Paper 26.3 (or a fork that runs Paper plugins, such as Purpur) on Java 25.

## Building

You need JDK 25 (Temurin) and Node 24. On macOS: `brew install --cask temurin@25 && brew install node`. On Nix: `nix develop`.

```bash
./gradlew build                       # compile, unit tests, both jars
./gradlew :adapters:paper:runServer   # Paper 26.3 in adapters/paper/run/ with both plugins installed
./gradlew scenarioTest                # a Paper server plus Mineflayer bots run the acceptance scenarios
```

The jars land in `adapters/paper/build/libs/Seasons-<version>.jar` and `web/build/libs/SeasonsWeb-<version>.jar`.

## Repository layout

```
api/                 Seasons API (seasons-api): public, platform-neutral
core/                all season logic; plain Java, no Minecraft imports; owns the ports
adapters/paper/      the Paper Seasons Adapter: implements the ports with public Paper API
integrations/        BlueMap and Discord, each enabled only when present
web/                 the Seasons Web plugin
testkit/             the test plugin and the Mineflayer scenario suite (testkit/bots)
docs/                spec, paper-api.md, questions.md, adr/
```

## Licence

Source-available under the [PolyForm Noncommercial License 1.0.0](LICENSE). Free for noncommercial use, such as a friends' server. Commercial use needs written permission from Peter Woodhead. Bundled libraries keep their own licences: see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
