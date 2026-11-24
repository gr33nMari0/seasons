// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// What the site says about releases, read from the repository at build time: the newest released
// version and its date from CHANGELOG.md, and the supported Minecraft versions from
// gradle.properties. Nothing here is typed by hand, so the site can't advertise a stale version.
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const ROOT = join(process.cwd(), '..')
const changelog = readFileSync(join(ROOT, 'CHANGELOG.md'), 'utf8')
const properties = readFileSync(join(ROOT, 'gradle.properties'), 'utf8')

const released = [...changelog.matchAll(/^## \[(\d+\.\d+\.\d+(?:-[\w.]+)?)\] - (\d{4}-\d{2}-\d{2})/gm)]
if (released.length === 0) throw new Error('CHANGELOG.md has no released version')

export const latest = { version: released[0][1], date: released[0][2] }
export const minecraft = (/^minecraftVersions=(.+)$/m.exec(properties)?.[1] ?? '').split(',').map((v) => v.trim()).filter(Boolean)
export const REPO = 'https://github.com/gr33nMari0/seasons'
export const downloads = {
  github: `${REPO}/releases/tag/v${latest.version}`,
  modrinth: 'https://modrinth.com/project/seasonsmc',
  hangar: 'https://hangar.papermc.io/gr33nMari0/SeasonsMC',
  seasonsJar: `${REPO}/releases/download/v${latest.version}/Seasons-${latest.version}.jar`,
  webJar: `${REPO}/releases/download/v${latest.version}/SeasonsWeb-${latest.version}.jar`,
  sums: `${REPO}/releases/download/v${latest.version}/SHA256SUMS`
}

/** The changelog's released sections, newest first, as Markdown. */
export function releases (): { version: string; date: string; body: string }[] {
  const parts = changelog.split(/^## /m).slice(1)
  return parts.flatMap((part) => {
    const m = /^\[(\d+\.\d+\.\d+(?:-[\w.]+)?)\] - (\d{4}-\d{2}-\d{2})\n/.exec(part)
    return m ? [{ version: m[1], date: m[2], body: part.slice(m[0].length).trim() }] : []
  })
}
