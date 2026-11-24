// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The built site (npm run build first): every page keeps a strict content security policy, runs no
// inline code but the hashed scripts its policy names, loads nothing from another site, raises no
// error or CSP violation, and every link inside it leads somewhere.
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { after, before, test } from 'node:test'
import { chromium } from 'playwright'
import { serve } from './serve.mjs'

export const ROOT = join(import.meta.dirname, '../dist')
let site

before(async () => { site = await serve() })
after(async () => { await site.close() })

export function pages (dir = ROOT) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return name === 'pagefind' || name === 'og' ? [] : pages(path)
    return name.endsWith('.html') ? [path] : []
  })
}

export const pathOf = (file) => '/' + relative(ROOT, file).replace(/index\.html$/, '')

test('the site was built', () => {
  assert.ok(pages().length > 3, 'run npm run build before npm test')
})

test('every page has a strict CSP, and only hashed inline scripts', () => {
  for (const file of pages()) {
    const html = readFileSync(file, 'utf8')
    const csp = /<meta http-equiv="Content-Security-Policy" content="([^"]+)"/.exec(html)?.[1]
    assert.ok(csp, `${file} has a CSP`)
    for (const part of ["default-src 'self'", "style-src 'self'", "object-src 'none'", "base-uri 'none'", "frame-src 'none'"]) {
      assert.ok(csp.includes(part), `${file}: ${part}`)
    }
    assert.ok(!/unsafe-inline|unsafe-eval'/.test(csp.replace("'wasm-unsafe-eval'", '')), `${file}: no unsafe-inline or unsafe-eval`)
    for (const m of html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g)) {
      if (!m[1].trim()) continue
      const hash = `'sha256-${createHash('sha256').update(m[1], 'utf8').digest('base64')}'`
      assert.ok(csp.includes(hash), `${file}: an inline script not in its CSP`)
    }
    assert.ok(!/<style|\sstyle="|\son[a-z]+="/i.test(html), `${file} has an inline style or handler`)
    assert.ok(!/<script[^>]+src="https?:/i.test(html) && !/<link[^>]+rel="stylesheet"[^>]+href="https?:/i.test(html), `${file} loads from another site`)
  }
})

test('every page loads only its own files, with no error or CSP violation, and its links resolve', async () => {
  const browser = await chromium.launch()
  try {
    const context = await browser.newContext()
    const requests = []
    const problems = []
    context.on('request', (r) => requests.push(r.url()))
    const page = await context.newPage()
    page.on('console', (m) => { if (m.type() === 'error') problems.push(`${page.url()}: ${m.text()}`) })
    page.on('pageerror', (e) => problems.push(`${page.url()}: ${e}`))
    const checked = new Set()
    for (const file of pages()) {
      const path = pathOf(file)
      if (path === '/404.html') continue
      const res = await page.goto(site.origin + path)
      assert.equal(res.status(), 200, path)
      const links = await page.locator('a[href]').evaluateAll((all) => all.map((a) => a.href))
      for (const href of links.filter((h) => h.startsWith(site.origin))) {
        const url = new URL(href)
        if (checked.has(url.pathname)) continue
        checked.add(url.pathname)
        const r = await fetch(site.origin + url.pathname)
        assert.equal(r.status, 200, `${path} links to ${url.pathname}`)
      }
    }
    assert.deepEqual(requests.filter((u) => !u.startsWith(site.origin)), [])
    assert.deepEqual(problems, [])
  } finally {
    await browser.close()
  }
})
