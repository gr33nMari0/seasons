// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Player-written text stays text on the dashboard: an item named like HTML, a waypoint and a link
// label with markup show exactly as written, make no element, run no script and break no rule of
// the page's content security policy.
import { MINUTE, assert } from '../lib/context.mjs'
import { buildGallery } from '../lib/hall.mjs'
import { dashboard, loginOnPage, openPage } from '../lib/browser.mjs'
import { uuid } from '../lib/web.mjs'

const NAME = '<img src=x onerror=alert(1)>'
const LABEL = '<script>alert(2)</script> Rules'
const WAYPOINT = '<b>Bold</b> Spot'

export default {
  name: 'm7-dashboard-text',
  timeoutMs: 3 * MINUTE,
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    const { origin } = await dashboard(ctx, admin, `links:\n  - { label: '${LABEL}', url: "https://example.com/rules" }\n`)
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await buildGallery(ctx, admin, { cells: 2 })
    await ctx.command(admin, `/season marker add hubs ${WAYPOINT} --at 10 -60 10`, /^Added waypoint /)
    await ctx.command(admin, `/give Alex minecraft:iron_sword[custom_name="${NAME}"]`, /^Gave 1 /)
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)

    const page = await openPage(ctx, origin, '/')
    const dialogs = []
    page.page.on('dialog', (d) => { dialogs.push(d.message()); d.dismiss().catch(() => {}) })
    const links = await page.waitForWidget('links', /Rules/)
    assert(links.includes(LABEL), `the label as written: ${links}`)
    const waypoints = await page.waitForWidget('waypoints', /Spot/)
    assert(waypoints.includes(WAYPOINT), `the waypoint as written: ${waypoints}`)
    await page.goto(`/player/${uuid('Alex')}`)
    await loginOnPage(ctx, page, alex)
    const profile = await page.waitForWidget('profile', /Iron Sword/)
    assert(profile.includes(NAME), `the item's name as written: ${profile}`)

    const made = await page.page.evaluate(() => ({
      images: document.querySelectorAll('img[src="x"]').length,
      scripts: [...document.querySelectorAll('script')].filter((s) => !s.src).length,
      bold: document.querySelectorAll('main b').length
    }))
    assert(made.images === 0 && made.scripts === 0 && made.bold === 0, `no element made from text: ${JSON.stringify(made)}`)
    assert(dialogs.length === 0, `no script ran: ${JSON.stringify(dialogs)}`)
    page.check('the page with markup in its text')
  }
}
