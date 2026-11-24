// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// After the build: draws every page's 1200 × 630 preview image (ADR 0011). Each page's head names
// its title, description, section and art (Head.astro); og/card.html lays them out in the site's
// own fonts and colours, and Chromium screenshots it to dist/og/<path>.png, where og:image points.
import { mkdirSync, readFileSync, readdirSync, statSync } from 'node:fs'
import http from 'node:http'
import { dirname, extname, join, normalize, relative } from 'node:path'
import { chromium } from 'playwright'

const SITE = join(import.meta.dirname, '..')
const DIST = join(SITE, 'dist')
const TYPES = { '.html': 'text/html', '.css': 'text/css', '.mjs': 'text/javascript', '.js': 'text/javascript', '.svg': 'image/svg+xml', '.woff2': 'font/woff2' }

function pages (dir = DIST) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return name === 'pagefind' || name === '_astro' ? [] : pages(path)
    return name.endsWith('.html') && name !== '404.html' ? [path] : []
  })
}

const decode = (s) => s.replace(/&#x27;|&#39;/g, "'").replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')
const meta = (html, key) => {
  const m = new RegExp(`<meta (?:property|name)="${key}" content="([^"]*)"`).exec(html)
  return m ? decode(m[1]) : ''
}

// The card and the design files, served from og/ and dist/.
const server = http.createServer((req, res) => {
  const path = decodeURIComponent(new URL(req.url, 'http://x').pathname)
  const root = path.startsWith('/og/') ? SITE : DIST
  const file = normalize(join(root, path))
  if (!file.startsWith(root)) { res.writeHead(404); res.end(); return }
  try {
    const body = readFileSync(file)
    res.writeHead(200, { 'Content-Type': TYPES[extname(file)] ?? 'application/octet-stream' })
    res.end(body)
  } catch { res.writeHead(404); res.end() }
})
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
const origin = `http://127.0.0.1:${server.address().port}`

const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1200, height: 630 } })
let count = 0
try {
  for (const file of pages()) {
    const html = readFileSync(file, 'utf8')
    const image = meta(html, 'og:image')
    if (!image) throw new Error(`${relative(DIST, file)} has no og:image`)
    const out = join(DIST, new URL(image).pathname)
    const title = meta(html, 'og:title').replace(/ \| Seasons$/, '')
    const q = new URLSearchParams({ title, description: meta(html, 'og:description'), section: meta(html, 'seasons:section'), art: meta(html, 'seasons:art') })
    await page.goto(`${origin}/og/card.html?${q}`)
    await page.waitForSelector('body[data-ready="true"]')
    mkdirSync(dirname(out), { recursive: true })
    await page.screenshot({ path: out, type: 'png' })
    count++
  }
} finally {
  await browser.close()
  server.close()
}
console.log(`${count} preview images drawn into dist/og/`)
