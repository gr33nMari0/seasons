// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The site loads nothing from another site, keeps its content security policy, breaks none of it,
// and every link inside it leads somewhere.
import assert from 'node:assert/strict'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { after, before, test } from 'node:test'
import { chromium } from 'playwright'
import { serve } from './serve.mjs'

const ROOT = join(import.meta.dirname, '../public')
let site

before(async () => { site = await serve() })
after(async () => { await site.close() })

function pages (dir = ROOT) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return pages(path)
    return name.endsWith('.html') ? [path] : []
  })
}

test('every page has a strict CSP and no inline script, style or handler', () => {
  for (const file of pages()) {
    const html = readFileSync(file, 'utf8')
    assert.match(html, /<meta http-equiv="Content-Security-Policy" content="default-src 'self';[^"]*object-src 'none'[^"]*">/, file)
    assert.ok(!/<script>|<style|\sstyle=|\son[a-z]+=/i.test(html), `${file} has inline code`)
    assert.ok(!/<script[^>]+src="https?:/i.test(html) && !/<link[^>]+href="https?:/i.test(html), `${file} loads from another site`)
  }
})

test('every page loads only its own files, with no CSP violation, and its links resolve', async () => {
  const browser = await chromium.launch()
  try {
    const context = await browser.newContext()
    const requests = []
    const problems = []
    context.on('request', (r) => requests.push(r.url()))
    const page = await context.newPage()
    page.on('console', (m) => { if (m.type() === 'error') problems.push(m.text()) })
    page.on('pageerror', (e) => problems.push(String(e)))
    for (const file of pages()) {
      const path = '/' + relative(ROOT, file).replace(/index\.html$/, '')
      const res = await page.goto(site.origin + path)
      assert.equal(res.status(), 200, path)
      const links = await page.locator('a[href]').evaluateAll((all) => all.map((a) => a.href))
      for (const href of links.filter((h) => h.startsWith(site.origin))) {
        const r = await fetch(href)
        assert.equal(r.status, 200, `${path} links to ${href}`)
      }
    }
    assert.deepEqual(requests.filter((u) => !u.startsWith(site.origin)), [])
    assert.deepEqual(problems, [])
  } finally {
    await browser.close()
  }
})
