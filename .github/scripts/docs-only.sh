#!/usr/bin/env bash
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
# Reads changed paths, one per line, and prints "true" if every one is documentation, else "false".
# CI skips the scenario shards for a docs-only change (.github/workflows/build.yml).
# Try it: git diff --name-only origin/main... | .github/scripts/docs-only.sh
#
# Documentation is exactly:
#   docs/**              the spec, ADRs, questions, progress, Paper API list
#   *.md at the root     README, CONTRIBUTING, CHANGELOG, SECURITY, AGENTS, CLAUDE, notices
#   LICENSE
# Anything else, including an empty list, is not docs-only, so the shards run.
set -euo pipefail

any=false
while IFS= read -r path || [ -n "$path" ]; do
  [ -z "$path" ] && continue
  any=true
  case "$path" in
    docs/*) ;;
    */*) echo false; exit 0 ;;
    *.md | LICENSE) ;;
    *) echo false; exit 0 ;;
  esac
done
echo "$any"
