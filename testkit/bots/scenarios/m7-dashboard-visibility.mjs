// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// web.yml's visibility on the page: with the region and the map hidden, leaderboards public and the
// feed for players, a visitor sees exactly the widgets and fields allowed and the page never asks
// for the hidden ones; logged in, the feed appears. With public: false a visitor sees only the
// login.
import { MINUTE, assert } from '../lib/context.mjs'
import { dashboard, loginOnPage, openPage } from '../lib/browser.mjs'
import { startMap, tunnelPort } from '../lib/tunnel.mjs'

export default {
  name: 'm7-dashboard-visibility',
  timeoutMs: 3 * MINUTE,
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    const map = await startMap(ctx, { headers: { 'Content-Security-Policy': `frame-ancestors https://localhost:${tunnelPort(ctx)}` } })
    const { origin } = await dashboard(ctx, admin, [
      'links:',
      `  - { label: Map, url: "${map}", embed: frame }`,
      'visibility:',
      '  online.region: hidden',
      '  map: hidden',
      '  leaderboards: public',
      '  event-feed: players',
      ''
    ].join('\n'))
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)

    const page = await openPage(ctx, origin, '/', { frames: [map] })
    const online = await page.waitForWidget('online', /Alex/)
    assert(/Overworld/.test(online), `the dimension still shows: ${online}`)
    assert(await page.page.locator('[data-widget="online"] [data-region]').count() === 0, `no region: ${online}`)
    await page.waitForWidget('leaderboards', /Deaths/i)
    const shown = await page.widgets()
    assert(!shown.includes('map') && !shown.includes('event-feed'), `no map and no feed for a visitor: ${JSON.stringify(shown)}`)
    assert(shown.includes('season') && shown.includes('links'), `the rest is there: ${JSON.stringify(shown)}`)
    assert(!page.apiPaths().includes('/api/v1/events'), 'the page never opened the feed')
    assert(!page.requests.some((u) => u.startsWith(map)), 'nor loaded the hidden map')
    const links = await page.widget('links')
    assert(!/Map/.test(links ?? ''), `the hidden map isn't a link either: ${links}`)

    await loginOnPage(ctx, page, alex)
    await page.waitForWidget('event-feed', /./)
    assert(!(await page.widgets()).includes('map'), 'the map stays hidden when logged in')
    page.check('the page with hidden widgets')

    // A closed dashboard: only the login.
    await dashboard(ctx, admin, 'public: false\n')
    const closed = await openPage(ctx, origin, '/')
    const widgets = await closed.widgets()
    assert(JSON.stringify(widgets) === '["login"]', `only the login: ${JSON.stringify(widgets)}`)
    assert(/log in/i.test(await closed.widget('login')), 'it asks to log in')
    closed.check('the closed dashboard')
  }
}
