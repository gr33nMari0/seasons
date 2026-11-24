// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// design/theme.js in Chromium and WebKit (questions 335): the page follows the system theme; the
// toggle switches it and is kept; a change of the system theme, live or between visits, wins at
// once and drops the toggle's choice; a locked page stays dark.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { test } from 'node:test'
import { chromium, webkit } from 'playwright'

const SCRIPT = readFileSync(join(import.meta.dirname, '../../design/theme.js'), 'utf8')
const page = (lock = '') => `<!doctype html><html${lock}><head><script src="/theme.js"></script></head>
<body><button type="button" data-theme-toggle data-label-light="Light theme" data-label-dark="Dark theme">Theme</button></body></html>`

async function open (browserType, colorScheme, lock) {
  const browser = await browserType.launch()
  const context = await browser.newContext({ colorScheme })
  await context.route('https://theme.test/**', (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/theme.js') return route.fulfill({ contentType: 'text/javascript', body: SCRIPT })
    return route.fulfill({ contentType: 'text/html', body: page(lock) })
  })
  const p = await context.newPage()
  await p.goto('https://theme.test/')
  return { browser, context, p }
}

const theme = (p) => p.evaluate(() => document.documentElement.dataset.theme)
const saved = (p) => p.evaluate(() => localStorage.getItem('seasons-theme'))

for (const [engine, browserType] of [['chromium', chromium], ['webkit', webkit]]) {
  test(`${engine}: follows the system and changes instantly`, async () => {
    const { browser, p } = await open(browserType, 'dark')
    try {
      assert.equal(await theme(p), 'dark')
      await p.emulateMedia({ colorScheme: 'light' })
      await p.waitForFunction(() => document.documentElement.dataset.theme === 'light')
      await p.emulateMedia({ colorScheme: 'dark' })
      await p.waitForFunction(() => document.documentElement.dataset.theme === 'dark')
    } finally {
      await browser.close()
    }
  })

  test(`${engine}: the toggle is kept until the system theme changes`, async () => {
    const { browser, context, p } = await open(browserType, 'dark')
    try {
      await p.click('[data-theme-toggle]')
      assert.equal(await theme(p), 'light')
      assert.equal(await p.getAttribute('[data-theme-toggle]', 'aria-pressed'), 'true')
      assert.equal(await p.getAttribute('[data-theme-toggle]', 'aria-label'), 'Dark theme')
      await p.reload()
      assert.equal(await theme(p), 'light', 'kept across a reload')
      await p.emulateMedia({ colorScheme: 'light' })
      await p.waitForFunction(() => localStorage.getItem('seasons-theme') === null)
      assert.equal(await theme(p), 'light', 'the system change dropped the choice')
      await p.emulateMedia({ colorScheme: 'dark' })
      await p.waitForFunction(() => document.documentElement.dataset.theme === 'dark')

      // A choice made against a dark system is dropped when the next visit finds it light.
      await p.click('[data-theme-toggle]')
      assert.equal(await theme(p), 'light')
      assert.notEqual(await saved(p), null)
      const next = await context.newPage()
      await next.emulateMedia({ colorScheme: 'light' })
      await next.goto('https://theme.test/')
      assert.equal(await theme(next), 'light')
      assert.equal(await saved(next), null)
      await next.emulateMedia({ colorScheme: 'dark' })
      await next.waitForFunction(() => document.documentElement.dataset.theme === 'dark')
    } finally {
      await browser.close()
    }
  })

  test(`${engine}: a locked page stays dark`, async () => {
    const { browser, p } = await open(browserType, 'light', ' data-theme-lock="dark"')
    try {
      assert.equal(await theme(p), 'dark')
      await p.click('[data-theme-toggle]')
      assert.equal(await theme(p), 'dark')
    } finally {
      await browser.close()
    }
  })
}
