// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The map, framed or linked: Seasons Web reads the map's own headers. A BlueMap whose CSP names the
// dashboard is framed, with a link under it; one that sends X-Frame-Options: DENY, one whose
// frame-ancestors name another site, and one that doesn't answer at all are a card linking to the
// map instead; and a frame that never loads gives way to the card after 10 seconds.
import { MINUTE, SECOND, assert } from '../lib/context.mjs'
import { dashboard, openPage } from '../lib/browser.mjs'
import { mapPort, startMap, tunnelPort } from '../lib/tunnel.mjs'
import { WebClient } from '../lib/web.mjs'

/** Points web.yml's map at `url` and waits for Seasons Web to have looked at its headers. */
async function mapAt (ctx, admin, url) {
  await dashboard(ctx, admin, `links:\n  - { label: Map, url: "${url}", embed: frame }\n`)
  const client = new WebClient(ctx)
  return ctx.eventually(async () => {
    const res = await client.get('/api/v1/dashboard')
    assert(res.status === 200 && res.json.map && res.json.map.framing !== 'checking', `the map looked at: ${res.text}`)
    return res.json.map.framing
  }, 20 * SECOND)
}

async function expectCard (ctx, page, url, what) {
  const card = page.page.locator('[data-widget="map"] a[data-map-link]')
  await card.first().waitFor({ timeout: 15 * SECOND })
  assert((await card.first().getAttribute('href')) === url, `${what}: the card links to the map`)
  assert(await page.page.locator('[data-widget="map"] iframe').count() === 0, `${what}: no frame`)
}

export default {
  name: 'm7-dashboard-map',
  timeoutMs: 4 * MINUTE,
  async run (ctx) {
    const admin = await ctx.admin()
    const dashboardOrigin = `https://localhost:${tunnelPort(ctx)}`
    const allowed = await startMap(ctx, { n: 0, headers: { 'Content-Security-Policy': `frame-ancestors ${dashboardOrigin}` } })
    const denied = await startMap(ctx, { n: 1, headers: { 'X-Frame-Options': 'DENY' } })
    const elsewhere = await startMap(ctx, { n: 2, headers: { 'Content-Security-Policy': "default-src 'self'; frame-ancestors https://other.example" } })
    const hanging = await startMap(ctx, { n: 3, hang: true })
    const nobody = `https://localhost:${mapPort(ctx, 4)}`

    // Framed, with a link under it.
    assert(await mapAt(ctx, admin, allowed) === 'allowed', 'a map that allows the dashboard')
    const { origin } = ctx.tunnel
    let page = await openPage(ctx, origin, '/', { frames: [allowed] })
    await page.page.frameLocator('[data-widget="map"] iframe').locator('#bluemap').waitFor({ timeout: 15 * SECOND })
    assert((await page.page.locator('[data-widget="map"] a[href]').first().getAttribute('href')) === allowed, 'a link under the frame')
    assert(/BlueMap.s own rules|hidden/i.test(await page.widget('map')), 'a note on who shows on the map')
    page.check('a framed map')
    await page.close()

    // Refused by the map's own headers.
    assert(await mapAt(ctx, admin, denied) === 'blocked', 'X-Frame-Options: DENY')
    page = await openPage(ctx, origin, '/', { frames: [denied] })
    await expectCard(ctx, page, denied, 'X-Frame-Options: DENY')
    await page.close()
    assert(await mapAt(ctx, admin, elsewhere) === 'blocked', 'frame-ancestors naming another site')
    page = await openPage(ctx, origin, '/', { frames: [elsewhere] })
    await expectCard(ctx, page, elsewhere, 'frame-ancestors elsewhere')
    await page.close()

    // Nothing answers.
    assert(await mapAt(ctx, admin, nobody) === 'unreachable', 'a map nobody answers for')
    page = await openPage(ctx, origin, '/', { frames: [nobody] })
    await expectCard(ctx, page, nobody, 'no answer')
    await page.close()

    // Answers too slowly to tell: framed, then the card after 10 seconds without a load.
    assert(await mapAt(ctx, admin, hanging) === 'unknown', 'a map too slow to tell')
    page = await openPage(ctx, origin, '/', { frames: [hanging] })
    assert(await page.page.locator('[data-widget="map"] iframe').count() === 1, 'framed while it might load')
    await expectCard(ctx, page, hanging, 'a frame that never loads')
    page.check('the map card')
  }
}
