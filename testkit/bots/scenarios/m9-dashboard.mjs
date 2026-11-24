// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The dashboard's M9 parts in a browser: preview tags in each page's HTML and the picture they
// name; Help with the guides bundled from the public docs and the server's own links; the login
// page as designed (the code in cells, the countdown bar, waiting for the player); the theme
// toggle; and editing a layout: a keyboard move announced aloud, a widget removed and added again,
// a shared layout naming a widget this viewer may not see, which shows as not available and asks
// the API nothing for it, and the layout kept in the browser across a reload.
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { SECOND, assert } from '../lib/context.mjs'
import { dashboard, openPage } from '../lib/browser.mjs'
import { WebClient } from '../lib/web.mjs'

const LAYOUT = join(import.meta.dirname, '../../../web/src/main/resources/com/petepeterepeat/seasons/web/dashboard/layout.mjs')

export default {
  name: 'm9-dashboard',
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    const { origin } = await dashboard(ctx, admin, [
      'name: Example SMP',
      'help:',
      '  links:',
      '    - { label: Server rules, url: "https://example.com/rules" }',
      ''
    ].join('\n'))
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    const shots = join(ctx.serverDir, 'm9-shots')
    mkdirSync(shots, { recursive: true })

    // Preview tags in the HTML a crawler reads, and the picture they name.
    const client = new WebClient(ctx)
    const home = await client.get('/')
    // Seasons' snapshot of the season refreshes every 30 s, so the title may not name it yet.
    assert(/<meta property="og:title" content="(Season 2 · )?Example SMP">/.test(home.text), 'the home page names the server')
    assert(/<meta name="twitter:card" content="summary_large_image">/.test(home.text), 'a large card')
    const image = /<meta property="og:image" content="([^"]+)">/.exec(home.text)?.[1] ?? ''
    assert(image.startsWith(origin + '/og/server/'), `the picture is on the dashboard's public address: ${image}`)
    const png = await client.get(new URL(image).pathname)
    assert(png.status === 200 && png.headers.get('content-type') === 'image/png', `the picture is drawn: ${png.status}`)

    const page = await openPage(ctx, origin, '/', { colorScheme: 'dark' })
    await page.page.screenshot({ path: join(shots, 'home-dark.png'), fullPage: true })

    // Help: the bundled guides and this server's own links.
    await page.page.click('nav a[href="/help"]')
    await page.waitForWidget('help-page', /Visiting the Hall.*Server rules/)
    await page.page.click('a[href="/help/visiting-the-hall"]')
    await page.waitForWidget('help-page', /Getting there/)

    // The login page, as designed.
    await page.goto('/')
    await page.page.click('[data-action="login"]')
    await page.page.waitForSelector('.login-view [data-login-code]', { timeout: 10 * SECOND })
    const code = (await page.page.locator('[data-login-code]').innerText()).trim()
    assert(await page.page.locator('.code-cell').count() === code.replace('-', '').length, 'one cell a character')
    assert(/Link your Minecraft account/.test(await page.text()), 'the page says what to do')
    assert(await page.page.locator('.login-bar b.on').count() > 0, 'the countdown bar is lit')
    await page.page.screenshot({ path: join(shots, 'login.png') })
    await ctx.command(alex, `/web link ${code}`, /^Linked: /)
    await page.page.waitForSelector('[data-viewer="Alex"]', { timeout: 15 * SECOND })
    assert(await page.page.locator('.login-view').count() === 0, 'the login page closes')

    // The theme toggle switches to parchment and back.
    await page.page.click('[data-theme-toggle]')
    assert(await page.page.evaluate(() => document.documentElement.dataset.theme) === 'light', 'light after the toggle')
    await page.page.screenshot({ path: join(shots, 'home-light.png'), fullPage: true })
    await page.page.click('[data-theme-toggle]')

    // Editing the layout.
    await page.page.click('[data-action="layout-edit"]')
    await page.page.waitForSelector('.edit-bar')
    await page.page.focus('[data-slot="season"]')
    await page.page.keyboard.press('ArrowRight')
    await page.page.keyboard.press('ArrowRight')
    await page.page.waitForFunction(() => /column 3/.test(document.querySelector('[role="status"][aria-live]')?.textContent ?? ''))
    await page.page.screenshot({ path: join(shots, 'editing.png'), fullPage: true })
    await page.page.focus('[data-slot="converter"]')
    await page.page.keyboard.press('Delete')
    assert(await page.page.locator('[data-slot="converter"]').count() === 0, 'removed')
    await page.page.selectOption('#layout-add', 'converter')
    await page.page.click('.edit-add button')
    await page.page.waitForSelector('[data-slot="converter"]')

    // A layout shared by an admin: the audit log isn't Alex's to see.
    const { encode } = await import(LAYOUT)
    const shared = encode({ name: 'Admin desk', items: [{ id: 'season', x: 0, y: 0, w: 4, h: 3 }, { id: 'audit-log', x: 4, y: 0, w: 8, h: 4 }] })
    await page.page.fill('.edit-paste input', shared)
    await page.page.click('.edit-paste button')
    await page.page.waitForSelector('[data-widget="unavailable"][data-slot="audit-log"]')
    await page.page.click('[data-action="layout-done"]')
    await page.goto('/')
    await page.page.waitForSelector('[data-widget="unavailable"][data-slot="audit-log"]')
    assert(!page.apiPaths().some((p) => p.startsWith('/api/v1/admin/')), 'nothing was asked for the widget Alex may not see')
    page.check('the dashboard')
  }
}
