#!/usr/bin/env bash
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
# Brings the Modrinth project page in line with docs/listing/: the fields in modrinth.json, the
# long description from modrinth.md, the icon and the gallery (.github/workflows/listing.yml).
# Safe to run again: fields, description and icon are replaced, and a gallery image is uploaded
# only if the project has no image with its title yet (to change one, delete it on Modrinth first).
# Needs MODRINTH_TOKEN (a personal access token with "Write projects") and the project's slug or id.
# Usage: .github/scripts/modrinth-listing.sh <project> [--dry-run]
set -euo pipefail

project=$1
dry=${2:-}
api=https://api.modrinth.com/v2
listing=docs/listing/modrinth.json
agent='seasons-listing (github.com/gr33nMari0/seasons)'

call () {
  if [ "$dry" = --dry-run ]; then echo "would $*"; return; fi
  curl --fail-with-body -sS -A "$agent" -H "Authorization: $MODRINTH_TOKEN" "$@"
  echo
}

# The fields and the long description, in one request.
jq --rawfile body docs/listing/modrinth.md '.project + {body: $body}' "$listing" > "${RUNNER_TEMP:-/tmp}/project.json"
echo "Updating the project's fields and description"
call -X PATCH -H 'Content-Type: application/json' --data-binary "@${RUNNER_TEMP:-/tmp}/project.json" "$api/project/$project"

echo "Uploading the icon"
call -X PATCH -H 'Content-Type: image/png' --data-binary "@$(jq -r .icon "$listing")" "$api/project/$project/icon?ext=png"

# A draft project is visible to its owner, so read it with the token.
if [ "$dry" = --dry-run ]; then
  existing='[]'
else
  existing=$(curl --fail-with-body -sS -A "$agent" -H "Authorization: $MODRINTH_TOKEN" "$api/project/$project" | jq '[.gallery[].title]')
fi
count=$(jq '.gallery | length' "$listing")
for ((i = 0; i < count; i++)); do
  item=$(jq -c ".gallery[$i]" "$listing")
  title=$(jq -r .title <<<"$item")
  if jq -e --arg t "$title" 'index($t) != null' <<<"$existing" > /dev/null; then
    echo "Gallery: \"$title\" is already there"
    continue
  fi
  echo "Gallery: uploading \"$title\""
  query=$(jq -r --argjson n "$i" '"ext=png&featured=\(.featured)&ordering=\($n)&title=\(.title | @uri)&description=\(.description | @uri)"' <<<"$item")
  call -X POST -H 'Content-Type: image/png' --data-binary "@$(jq -r .file <<<"$item")" "$api/project/$project/gallery?$query"
done
