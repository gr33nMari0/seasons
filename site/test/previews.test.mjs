// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Rich link previews (npm run build first): every page names its title, description, canonical
// address and a 1200 × 630 PNG preview image that exists, for Open Graph and Twitter cards.
import assert from 'node:assert/strict'
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { test } from 'node:test'

const ROOT = join(import.meta.dirname, '../dist')
const SITE = 'https://seasonsmc.petepeterepeat.com'

function pages (dir = ROOT) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return name === 'pagefind' || name === 'og' ? [] : pages(path)
    return name.endsWith('.html') && name !== '404.html' ? [path] : []
  })
}

const meta = (html, key) => new RegExp(`<meta (?:property|name)="${key}" content="([^"]*)"`).exec(html)?.[1]

test('every page has the tags for a rich preview and its image', () => {
  const files = pages()
  assert.ok(files.length > 3, 'run npm run build before npm test')
  for (const file of files) {
    const html = readFileSync(file, 'utf8')
    const at = relative(ROOT, file)
    for (const key of ['og:title', 'og:description', 'og:url', 'og:site_name', 'og:image', 'og:image:alt', 'twitter:card', 'twitter:image', 'theme-color']) {
      assert.ok(meta(html, key), `${at}: ${key}`)
    }
    assert.equal(meta(html, 'twitter:card'), 'summary_large_image', at)
    assert.equal(meta(html, 'og:image:width'), '1200', at)
    assert.equal(meta(html, 'og:image:height'), '630', at)
    assert.match(html, /<link rel="canonical" href="https:\/\/seasonsmc\.petepeterepeat\.com\//, at)
    const image = meta(html, 'og:image')
    assert.ok(image.startsWith(`${SITE}/og/`), `${at}: ${image}`)
    const png = join(ROOT, new URL(image).pathname)
    assert.ok(existsSync(png), `${at}: ${png} was drawn`)
    const head = readFileSync(png).subarray(0, 24)
    assert.equal(head.toString('hex', 0, 8), '89504e470d0a1a0a', `${at}: a PNG`)
    assert.equal(head.readUInt32BE(16), 1200, `${at}: 1200 wide`)
    assert.equal(head.readUInt32BE(20), 630, `${at}: 630 high`)
  }
})
