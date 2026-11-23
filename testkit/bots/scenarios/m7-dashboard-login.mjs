// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Logging in on the page, in Chromium and in WebKit (every iPad's engine): the page shows a code,
// the player types /web link, and the page becomes theirs without reloading; no cookie is readable
// by the page's script. An expired code offers a new one. /web logout all sends the page back to
// the public view with a notice.
import { MINUTE, SECOND, assert } from '../lib/context.mjs'
import { dashboard, loginOnPage, openPage } from '../lib/browser.mjs'

export default {
  name: 'm7-dashboard-login',
  timeoutMs: 4 * MINUTE,
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    const { origin } = await dashboard(ctx, admin)
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)

    for (const engine of ['chromium', 'webkit']) {
      const page = await openPage(ctx, origin, '/', { engine })
      await page.page.evaluate(() => { window.__notReloaded = true })
      await loginOnPage(ctx, page, alex)
      assert(await page.page.evaluate(() => window.__notReloaded === true), `${engine}: logged in without a reload`)
      assert(await page.page.evaluate(() => document.cookie) === '', `${engine}: no cookie the page's script can read`)
      await page.waitForWidget('leaderboards', /Deaths/i)
      page.check(`logging in on ${engine}`)
      await page.close()
    }

    // An expired code: the page offers a new one, which works.
    const page = await openPage(ctx, origin, '/')
    await page.page.locator('[data-action="login"]').first().click()
    const first = (await page.page.locator('[data-login-code]').first().innerText()).trim()
    await ctx.advance(admin, 5 * MINUTE + 10 * SECOND)
    await page.page.locator('[data-action="new-code"]').first().waitFor({ timeout: 15 * SECOND })
    await ctx.command(alex, `/web link ${first}`, /^That code isn't valid or has expired\. /)
    await page.page.locator('[data-action="new-code"]').first().click()
    await ctx.eventually(async () => {
      const next = (await page.page.locator('[data-login-code]').first().innerText()).trim()
      assert(/^[A-Z0-9]{4}-[A-Z0-9]{4}$/.test(next) && next !== first, `a new code: ${next}`)
    })
    const second = (await page.page.locator('[data-login-code]').first().innerText()).trim()
    await ctx.command(alex, `/web link ${second}`, /^Linked: /)
    await page.page.waitForSelector('[data-viewer="Alex"]', { timeout: 15 * SECOND })

    // Logged out from the game: back to the public view, with a notice.
    await ctx.command(alex, '/web logout all', /^Logged out of /)
    await page.page.waitForSelector('[data-notice="logged-out"]', { timeout: 45 * SECOND })
    await page.waitForNoWidget('leaderboards', 15 * SECOND)
    assert(await page.page.locator('[data-viewer]').count() === 0, 'nobody is logged in')
    page.check('logging in and out')
  }
}
