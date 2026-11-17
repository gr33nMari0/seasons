# Open questions

Ambiguities found while building, the reading taken, and what to confirm. Newest last.

## M0

1. **Java package and Maven group.** The spec names no package. Taken: `com.petepeterepeat.seasons` (from the play.petepeterepeat.com domain in the runbook), with `com.petepeterepeat.seasons.lib` for relocated libraries. Renaming later is a mechanical refactor, but it changes the persistent-data keys (`seasons:` namespaces are unaffected; only class names move).
2. **Mineflayer trails 26.3.** Mineflayer 4.39.0 (the latest) supports up to Minecraft 26.1. Taken: the bots speak 26.1 and the scenario servers install ViaVersion and ViaBackwards so they can join a 26.3 server. Test servers only; recorded in `docs/adr/0001-viaversion-on-scenario-servers.md`. Drop them when Mineflayer supports the newest drop. Confirm this is acceptable, since bots then see the world through a protocol translation.
3. **bStats plugin ids.** Each jar needs its own bStats plugin id, registered on bstats.org by the owner. Taken: bStats is pinned in `libs.versions.toml` but not yet shaded or started; it is wired in once the ids exist (by M6, the public release). `THIRD_PARTY_NOTICES.md` will list it then.
4. **Curation permission nodes.** The spec says `seasons.curate.*` without naming the leaves. Taken: they are declared in M4 with the curation commands, default `op`.
5. **"Every supported version" in CI.** v1 supports only 26.3, so the spec's `build.yml` (one job) covers it. Taken: `minecraftVersions` in `gradle.properties` lists the versions, and `scenarioTest` runs one scenario server per entry, so adding the next drop is a one-line change. The CI compile against the newest Paper API with deprecations as errors comes with M6.
6. **Branch names.** The repository's first commit is on `master`, while the spec and the workflows use `main`. Taken: M0 is on `m0-skeleton`; rename `master` to `main` before creating the GitHub repository.
7. **Default branch protection and the GitHub repository** are not created by M0; they need the owner's account (`gh repo create seasons --public|--private --source . --push`).
