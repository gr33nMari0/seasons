// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The admin permission split and the paged audit log (M10 plan, R9 and L2): seasons.web.admin.audit
// alone shows the audit log but not server health or the players waiting; the audit API pages back
// through older entries with a cursor, filters by kind and by player, and still answers its 1.4
// shape with no query; on the page the log scrolls inside its card, with a labelled region the
// keyboard can scroll, and a button that loads older entries; seasons.web.admin shows everything.
import { MINUTE, assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { dashboard, loginOnPage, openPage } from '../lib/browser.mjs'
import { WebClient, login } from '../lib/web.mjs'
import { box } from '../lib/zones.mjs'

export default {
  name: 'm10-admin-audit',
  timeoutMs: 5 * MINUTE,
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    const { origin } = await dashboard(ctx, admin)
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await box(ctx, admin, 'old-base', { x: 500, y: FLOOR - 1, z: 500 }, { x: 520, y: FLOOR + 10, z: 520 })
    // 60 entries: each unlock and lock is audited.
    for (let i = 0; i < 30; i++) {
      await ctx.command(admin, '/season unlock old-base', /^Zone old-base is unlocked/)
      await ctx.command(admin, '/season lock old-base', /^Zone old-base is locked again/)
    }

    await ctx.command(admin, `/scenario permit ${admin.username} seasons.web.admin.audit`, /^Permitted /)
    const api = new WebClient(ctx)
    await login(ctx, admin, api)
    assert((await api.get('/api/v1/admin/health')).status === 403, 'audit alone: no server health')
    assert((await api.get('/api/v1/admin/pending')).status === 403, 'audit alone: no players waiting')

    // The 1.4 shape, unchanged: the last 50, oldest first.
    const plain = await api.get('/api/v1/admin/audit')
    assert(plain.status === 200 && plain.json.entries.length === 50, `50 entries: ${plain.json?.entries?.length}`)
    const times = plain.json.entries.map((e) => e.time)
    assert(times.every((t, i) => i === 0 || t >= times[i - 1]), 'oldest first')
    assert(typeof plain.json.next === 'string', 'a cursor to older entries')

    // Older entries, with no overlap and nothing skipped.
    const older = await api.get(`/api/v1/admin/audit?limit=50&before=${encodeURIComponent(plain.json.next)}`)
    assert(older.status === 200, `older: ${older.status}`)
    const all = [...older.json.entries, ...plain.json.entries]
    const locks = all.filter((e) => e.action === 'zone-lock' || e.action === 'zone-unlock')
    assert(locks.length === 60, `every lock and unlock across the two pages: ${locks.length}`)
    assert(older.json.entries.every((e) => e.time <= plain.json.entries[0].time), 'the older page is older')

    const kind = await api.get('/api/v1/admin/audit?kind=zone-unlock&limit=100')
    assert(kind.json.entries.length === 30 && kind.json.entries.every((e) => e.action === 'zone-unlock'),
      `the kind filter: ${kind.json.entries.length}`)
    const nobody = await api.get('/api/v1/admin/audit?player=Alex')
    assert(nobody.json.entries.length === 0, 'nothing Alex did')
    const mine = await api.get(`/api/v1/admin/audit?player=${admin.username}&limit=10`)
    assert(mine.json.entries.length === 10 && mine.json.entries.every((e) => e.actor === admin.username || e.target === admin.username),
      'the player filter')
    assert((await api.get('/api/v1/admin/audit?limit=0')).status === 400, 'a limit out of range is refused')

    const player = new WebClient(ctx)
    await login(ctx, alex, player)
    assert((await player.get('/api/v1/admin/audit')).status === 403, 'a player has no audit log')

    // On the page: the log scrolls inside its card and loads older entries.
    const page = await openPage(ctx, origin, '/')
    await loginOnPage(ctx, page, admin)
    await page.waitForWidget('audit-log', /zone-lock/)
    assert(await page.widget('server-health') === null, 'no health widget with audit alone')
    const region = page.page.locator('[data-widget="audit-log"] [role="region"]')
    assert(await region.count() === 1, 'the log is a region')
    const scroll = await region.evaluate((e) => ({
      overflow: getComputedStyle(e).overflowY,
      scrolls: e.scrollHeight > e.clientHeight,
      tab: e.tabIndex,
      label: e.getAttribute('aria-label')
    }))
    assert(scroll.overflow === 'auto' && scroll.scrolls, `the log scrolls inside its card: ${JSON.stringify(scroll)}`)
    assert(scroll.tab === 0 && scroll.label, `the keyboard can scroll it: ${JSON.stringify(scroll)}`)
    const before = await page.page.locator('[data-widget="audit-log"] li').count()
    await page.page.locator('[data-widget="audit-log"] button.older').click()
    await ctx.eventually(async () => {
      const now = await page.page.locator('[data-widget="audit-log"] li').count()
      assert(now > before, `older entries loaded: ${before} -> ${now}`)
    })
    page.check('the audit log')
    await page.close()

    await ctx.command(admin, `/scenario permit ${admin.username} seasons.web.admin`, /^Permitted /)
    await ctx.eventually(async () => {
      const health = await api.get('/api/v1/admin/health')
      assert(health.status === 200, `seasons.web.admin shows server health: ${health.status}`)
    }, 45_000)
  }
}
