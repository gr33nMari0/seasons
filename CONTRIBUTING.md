# Contributing to Seasons

Thanks for helping. Please read this before opening a pull request.

## Licence grant

Seasons is source-available under the PolyForm Noncommercial License 1.0.0, and Peter Woodhead keeps the copyright so he can grant commercial licences. **By submitting a contribution (code, documentation or anything else) you agree that Peter Woodhead may use, change, relicense and sublicense it on any terms, including commercial terms**, and you confirm you have the right to grant that. Contributions are accepted only on that basis.

## How to work

- `docs/spec.md` is the source of truth. If something is ambiguous, note it in `docs/questions.md`.
- Branch from `main`, keep commits small with [conventional messages](https://www.conventionalcommits.org/), and open a pull request. The `build` check must pass.
- Run `./gradlew build scenarioTest` before pushing. `./gradlew spotlessApply` fixes formatting and adds the licence header.
- Every source file starts with `// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0`.
- Public Paper API only, and list every Paper API you call in `docs/paper-api.md`.
- Player-facing text goes in `messages.yml`.
- Add a changelog entry for every behaviour change.
- No new dependency without a short record in `docs/adr/`. After any dependency change (Dependabot pull requests included), refresh `gradle/verification-metadata.xml` with `./gradlew --write-verification-metadata sha256 build scenarioTest` and commit it. Run it with an empty Gradle cache (`GRADLE_USER_HOME=$(mktemp -d)`) so it records everything CI will download.

## Reporting dupes or exploits

Never in a public issue. See [SECURITY.md](SECURITY.md).
