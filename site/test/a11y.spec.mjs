// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Accessibility (ADR 0010; npm run build first): axe finds no serious or critical problem on any
// page in the dark theme at desktop width, nor on the main pages in both themes at phone width, and
// no page scrolls sideways on a phone.
import AxeBuilder from '@axe-core/playwright'
import assert from 'node:assert/strict'
import { readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { after, before, test } from 'node:test'
import { chromium } from 'playwright'
import { serve } from './serve.mjs'

const ROOT = join(import.meta.dirname, '../dist')
const MAIN = ['/', '/docs/', '/download/', '/connect/', '/changelog/', '/docs/start/install/', '/docs/reference/commands/']
let site
let browser

before(async () => { site = await serve(); browser = await chromium.launch() })
after(async () => { await browser.close(); await site.close() })

function pages (dir = ROOT) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return name === 'pagefind' || name === 'og' ? [] : pages(path)
    return name === 'index.html' ? ['/' + relative(ROOT, path).replace(/index\.html$/, '')] : []
  })
}

async function audit (path, { width, colorScheme }) {
  const context = await browser.newContext({ viewport: { width, height: 900 }, colorScheme })
  try {
    const page = await context.newPage()
    await page.goto(site.origin + path)
    const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze()
    const bad = result.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical')
    const wide = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1)
    return { bad, wide }
  } finally {
    await context.close()
  }
}

const describe = (bad) => bad.map((v) => `${v.id}: ${v.nodes.slice(0, 3).map((n) => n.target.join(' ')).join(', ')}`).join('\n')

test('every page, dark, desktop', async () => {
  for (const path of pages()) {
    const { bad } = await audit(path, { width: 1280, colorScheme: 'dark' })
    assert.equal(bad.length, 0, `${path}\n${describe(bad)}`)
  }
})

for (const colorScheme of ['dark', 'light']) {
  test(`the main pages, ${colorScheme}, phone`, async () => {
    for (const path of MAIN.filter((p) => pages().includes(p))) {
      const { bad, wide } = await audit(path, { width: 375, colorScheme })
      assert.equal(bad.length, 0, `${path}\n${describe(bad)}`)
      assert.equal(wide, false, `${path} scrolls sideways at 375 px`)
    }
  })
}
