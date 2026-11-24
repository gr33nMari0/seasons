// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The public site's connect page end to end: the built site (site/dist, from :testkit:buildSite)
// served from its own address as GitHub Pages serves it, given the dashboard's address, reads
// /.well-known/seasons.json there, names the server and goes to its dashboard, where logging in
// works. A localhost address asks no public DNS, so nothing leaves the machine.
import { join } from 'node:path'
import { SECOND, assert } from '../lib/context.mjs'
import { dashboard, loginOnPage, openPage } from '../lib/browser.mjs'
import { startSite, tunnelPort } from '../lib/tunnel.mjs'

const SITE = join(import.meta.dirname, '../../../site/dist')

export default {
  name: 'm7-connect',
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    const { origin } = await dashboard(ctx, admin, 'name: Example SMP\n')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    const site = await startSite(ctx, SITE)

    const page = await openPage(ctx, site, '/connect/', { frames: [origin] })
    await page.page.locator('input[name="server"]').fill(`https://localhost:${tunnelPort(ctx)}`)
    await page.page.locator('form[data-connect] button[type="submit"]').click()
    await page.page.waitForURL(`${origin}/`, { timeout: 15 * SECOND })
    await page.page.waitForSelector('body[data-ready="true"]', { timeout: 15 * SECOND })
    assert(/Example SMP/.test(await page.text()), 'the dashboard of the server named')
    page.origin = origin
    await loginOnPage(ctx, page, alex)
    await page.waitForWidget('leaderboards', /Deaths/i)
    page.frames.push(site)
    page.check('connecting from the public site')
  }
}
