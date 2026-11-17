# Changelog

Every behaviour change gets an entry. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and both plugins use [semantic versioning](https://semver.org/).

## [Unreleased]

### Added

- Season lifecycle (M1 part 1): Active (Frontier and Open stages), Ending (countdown, then archiving) and Intermission, kept in `<world>/seasons/data/season.json` and resumed after a restart. A restart during the end countdown cancels it and tells admins.
- `/season init <season>` records the season in progress on a server that has never run Seasons; refused once any season is recorded.
- `/season schedule end <time>` sets the planned end (ISO time with a time zone) in `seasons/<n>.yml`. Reminders go out at 7 days, 1 day, 1 hour and 10 minutes before it, in chat and on Discord; nothing ends at the planned time unless `schedule.autoEnd` is on.
- `/season end [--dry-run] [--now]` starts the boss-bar countdown (`schedule.endCountdown`, 10 minutes by default), or skips it, then reaches the Intermission. Archiving itself is a stub until M1 part 2: no items move yet.
- `/season cancel` stops the countdown before archiving starts.
- `/season status` shows the phase, planned end (server time and UTC), countdown, journal health and startup config problems; `/season info` shows players the phase and planned end.
- `config.yml` with every setting and its default, `secrets.yml` for webhook URLs (or the `SEASONS_DISCORD_WEBHOOK` and `SEASONS_ADMIN_WEBHOOK` environment variables), and validation. `/season reload` swaps every file in at once or keeps the previous config and reports file, line and message; `/season validate` only checks.
- The journal (`data/journal.log`): append-only, fsynced before the change it covers, with a checksum per line, damaged-line repair, compaction and a crash-recovery framework that later milestones plug into.
- Discord webhook sender: posts in order on its own thread with backoff, never pings anyone, never logs the URL.
- Scenario harness: one fresh server per scenario, scenarios with several sessions and a restart between them, and an https webhook receiver.
- M0 skeleton: Gradle build with Java 25, Error Prone, NullAway and Spotless; the `api`, `core`, `integrations`, `adapters/paper`, `web` and `testkit` modules.
- The core's ports, implemented by the Paper adapter, with declared adapter capabilities.
- `/season status` (ops): plugin version, season, and any adapter capability gaps. Text comes from `messages.yml`.
- `paper-plugin.yml` for Seasons and Seasons Web, with every permission node and its default.
- Scenario harness: `./gradlew scenarioTest` starts Paper with run-paper and drives it with Mineflayer bots; the NBT neutrality test opens item data written by the adapter with a plain NBT reader.
- Build and release workflows, Dependabot.
