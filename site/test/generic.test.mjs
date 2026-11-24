// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The public site and the README are generic (docs/plans/m9-design-overhaul.md): no server's own
// name, addresses or players. A server's own text belongs in its own dashboard's web.yml.
import assert from 'node:assert/strict'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { test } from 'node:test'

const REPO = join(import.meta.dirname, '../..')
const FORBIDDEN = [/TheRanch/i, /The Ranch/i, /Harryz101/, /\bTaran\b/, /(?<!seasonsmc\.)petepeterepeat\.com/]

function files (dir, keep) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return name === 'og' || name === 'pagefind' ? [] : files(path, keep)
    return keep(name) ? [path] : []
  })
}

test('no server-specific names on the public site, in its sources or in the README', () => {
  const sources = [
    ...files(join(REPO, 'site/dist'), (n) => /\.(html|xml|txt|json)$/.test(n)),
    ...files(join(REPO, 'site/src'), (n) => /\.(astro|md|mdx|ts|mjs|css|json)$/.test(n)),
    join(REPO, 'README.md')
  ]
  for (const file of sources) {
    const text = readFileSync(file, 'utf8')
    for (const word of FORBIDDEN) assert.ok(!word.test(text), `${relative(REPO, file)} mentions ${word}`)
  }
})
