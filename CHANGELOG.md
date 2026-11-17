# Changelog

Every behaviour change gets an entry. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and both plugins use [semantic versioning](https://semver.org/).

## [Unreleased]

### Added

- M0 skeleton: Gradle build with Java 25, Error Prone, NullAway and Spotless; the `api`, `core`, `integrations`, `adapters/paper`, `web` and `testkit` modules.
- The core's ports, implemented by the Paper adapter, with declared adapter capabilities.
- `/season status` (ops): plugin version, season, and any adapter capability gaps. Text comes from `messages.yml`.
- `paper-plugin.yml` for Seasons and Seasons Web, with every permission node and its default.
- Scenario harness: `./gradlew scenarioTest` starts Paper with run-paper and drives it with Mineflayer bots; the NBT neutrality test opens item data written by the adapter with a plain NBT reader.
- Build and release workflows, Dependabot.
