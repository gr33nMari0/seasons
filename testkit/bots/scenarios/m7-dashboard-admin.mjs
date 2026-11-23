// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The admin widgets: with seasons.web.admin, server health, the players waiting to be archived (Sam,
// who played Season 2 and was offline when it ended) and Seasons' audit log without positions; a
// player without the node, and an op without it, see none of them.
import { MINUTE, assert } from '../lib/context.mjs'
import { FLOOR, buildGallery } from '../lib/hall.mjs'
import { dashboard, loginOnPage, openPage } from '../lib/browser.mjs'
import { box } from '../lib/zones.mjs'

const ADMIN_WIDGETS = ['server-health', 'pending-players', 'audit-log']

export default {
  name: 'm7-dashboard-admin',
  timeoutMs: 4 * MINUTE,
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    const sam = await ctx.join('Sam')
    const { origin } = await dashboard(ctx, admin)
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await buildGallery(ctx, admin, { cells: 4 })
    await box(ctx, admin, 'old-base', { x: 500, y: FLOOR - 1, z: 500 }, { x: 520, y: FLOOR + 10, z: 520 })
    await ctx.leave(sam)
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)

    // An op alone sees nothing of an admin's.
    const opPage = await openPage(ctx, origin, '/')
    await loginOnPage(ctx, opPage, admin)
    await opPage.waitForWidget('leaderboards', /Deaths/i)
    for (const w of ADMIN_WIDGETS) assert(await opPage.widget(w) === null, `an op without seasons.web.admin has no ${w}`)
    await opPage.close()

    const player = await openPage(ctx, origin, '/')
    await loginOnPage(ctx, player, alex)
    await player.waitForWidget('leaderboards', /Deaths/i)
    for (const w of ADMIN_WIDGETS) assert(await player.widget(w) === null, `a player has no ${w}`)

    await ctx.command(admin, `/scenario permit ${admin.username} seasons.web.admin`, /^Permitted /)
    const page = await openPage(ctx, origin, '/')
    await loginOnPage(ctx, page, admin)
    const health = await page.waitForWidget('server-health', /TPS/)
    assert(/Tick/i.test(health) && /Memory/i.test(health), `server health: ${health}`)
    const pending = await page.waitForWidget('pending-players', /Sam/)
    assert(/Season 2/.test(pending) && !/Alex/.test(pending), `Sam waits to be archived, Alex doesn't: ${pending}`)
    const audit = await page.waitForWidget('audit-log', /archive/i)
    assert(/zone/i.test(audit), `the zone edit is in the log: ${audit}`)
    assert(!/-?\d+,\s*-?\d+,\s*-?\d+/.test(audit) && !/\b[xyz]\s*[:=]\s*-?\d/i.test(audit), `no positions in the log: ${audit}`)
    page.check('the admin widgets')
  }
}
