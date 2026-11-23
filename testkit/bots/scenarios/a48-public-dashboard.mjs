// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A48. Without logging in, the dashboard shows the season clock, who's online, the event feed, the
// map and the Hall, but no leaderboards or full profiles; logged-in responses carry
// Cache-Control: private, no-store.
// First the API the dashboard reads, then the page itself in a browser behind the tunnel.
import { SECOND, assert } from '../lib/context.mjs'
import { buildGallery } from '../lib/hall.mjs'
import { dashboard, loginOnPage, openPage } from '../lib/browser.mjs'
import { startMap, tunnelPort } from '../lib/tunnel.mjs'
import { WebClient, assertPrivate, assertPublic, login, uuid, waitForEvent } from '../lib/web.mjs'

/** Text that would be a position: "x: 12", "z=-40" or "12, -60, 40". */
const COORDINATES = /\b[xyz]\s*[:=]\s*-?\d|-?\d+,\s*-?\d+,\s*-?\d+/i

export default {
  name: 'a48-public-dashboard',
  timeoutMs: 240 * SECOND,
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    // A BlueMap that lets the dashboard frame it, as TheRanch's additional-headers does.
    const MAP = await startMap(ctx, { headers: { 'Content-Security-Policy': `frame-ancestors https://localhost:${tunnelPort(ctx)}` } })
    const { origin } = await dashboard(ctx, admin, `links:\n  - { label: Map, url: "${MAP}", embed: frame }\n  - { label: Discord, url: "https://discord.gg/example" }\n`)
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await buildGallery(ctx, admin, { cells: 2 })
    await ctx.command(admin, '/give Alex minecraft:diamond_sword[custom_name="Edge",enchantments={sharpness:5}]', /^Gave 1 /)
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20 * SECOND)
    await ctx.eventually(async () => assert(await ctx.itemCount(admin, 'Alex') === 0, 'Alex is archived'))

    const visitor = new WebClient(ctx)

    // The season clock.
    // The season is looked at every second.
    const season = await ctx.eventually(async () => {
      const res = await visitor.get('/api/v1/season')
      assert(res.status === 200, `the season is public: ${res.status}`)
      assert(res.json.season === 2 && res.json.phase === 'intermission', `Season 2's Intermission: ${res.text}`)
      return res
    })
    assertPublic(season, 'the season without a login')

    // Who's online, with the area they're in and never coordinates.
    const online = await ctx.eventually(async () => {
      const res = await visitor.get('/api/v1/players/online')
      assert(res.status === 200, `who's online is public: ${res.status}`)
      assert(res.json.players.some((p) => p.name === 'Alex'), `Alex is online: ${res.text}`)
      return res
    })
    const seen = online.json.players.find((p) => p.name === 'Alex')
    assert(seen.uuid === uuid('Alex'), `Alex's uuid: ${seen.uuid}`)
    assert(typeof seen.dimension === 'string' && seen.region && typeof seen.region.name === 'string', `Alex's dimension and region: ${JSON.stringify(seen)}`)
    assert(!/"[xyz]"\s*:/.test(online.text), `no coordinates: ${online.text}`)
    assertPublic(online, "who's online without a login")

    // The event feed: a join reaches a visitor's stream.
    const feed = await visitor.stream()
    assert(feed.status === 200, `the event feed is public: ${feed.status} ${feed.text}`)
    assert(/^text\/event-stream/.test(feed.headers.get('content-type')), 'an event stream')
    await ctx.join('Sam')
    await waitForEvent(feed, (e) => e.event === 'join' && e.data.player?.name === 'Sam', 15 * SECOND, "Sam's join")

    // The map, as a link the dashboard frames.
    const links = await visitor.get('/api/v1/links')
    assert(links.status === 200 && links.json.links.some((l) => l.url === MAP && l.embed === 'frame'), `the map link: ${links.text}`)

    // The Hall: the gallery and Alex's cell, without gear.
    const hall = await visitor.get('/api/v1/hall/2')
    assert(hall.status === 200, `the Hall is public: ${hall.status}`)
    assert(hall.json.cells.some((c) => c.owner?.name === 'Alex'), `Alex's cell: ${hall.text}`)
    assert(!/"[xyz]"\s*:/.test(hall.text), `no coordinates in the Hall: ${hall.text}`)
    const cell = await visitor.get(`/api/v1/hall/2/${uuid('Alex')}`)
    assert(cell.status === 200 && cell.json.name === 'Alex', `Alex's Season 2 archive: ${cell.text}`)
    assert(cell.json.gear === undefined, `no gear without a login: ${cell.text}`)

    // No leaderboards, and only the public part of a profile.
    const boards = await visitor.get('/api/v1/leaderboards?season=2')
    assert(boards.status === 401, `leaderboards need a login: ${boards.status}`)
    assertPrivate(boards, 'a refusal')
    const profile = await visitor.get(`/api/v1/players/${uuid('Alex')}`)
    assert(profile.status === 200 && profile.json.name === 'Alex', `Alex's profile summary: ${profile.text}`)
    assert(profile.json.seasons.some((s) => s.season === 2), `the seasons Alex played: ${profile.text}`)
    assert(profile.json.seasons.every((s) => s.statistics === undefined && s.playTime === undefined), `no statistics without a login: ${profile.text}`)

    // Logged in, the same addresses say more, and none of it is cached for anyone else.
    const phone = new WebClient(ctx)
    await login(ctx, alex, phone)
    const own = await phone.get(`/api/v1/players/${uuid('Alex')}`)
    assert(own.status === 200 && own.json.seasons.some((s) => s.season === 2 && s.statistics && typeof s.statistics['minecraft:play_time'] === 'number'), `Alex's full profile: ${own.text}`)
    assertPrivate(own, 'a profile with a login')
    const ranked = await phone.get('/api/v1/leaderboards?season=2')
    assert(ranked.status === 200 && Array.isArray(ranked.json.boards.deaths), `leaderboards with a login: ${ranked.text}`)
    assertPrivate(ranked, 'leaderboards with a login')
    const gear = await phone.get(`/api/v1/hall/2/${uuid('Alex')}`)
    const sword = gear.json.gear?.find((g) => g.id === 'minecraft:diamond_sword')
    assert(sword && sword.name === 'Edge' && sword.enchantments.some((e) => e.id === 'minecraft:sharpness' && e.level === 5), `Alex's sword as text: ${gear.text}`)
    assertPrivate(gear, 'archived gear with a login')
    for (const path of ['/api/v1/season', '/api/v1/players/online', '/api/v1/hall/2', '/api/v1/links']) {
      assertPrivate(await phone.get(path), `${path} with a login`)
    }

    // The page, without logging in: the clock, who's online, the feed, the map and the Hall.
    const page = await openPage(ctx, origin, '/', { frames: [MAP] })
    await page.waitForWidget('season', /Season 2/)
    await page.waitForWidget('season', /Intermission/i)
    const shownOnline = await page.waitForWidget('online', /Alex/)
    assert(/Overworld/.test(shownOnline), `Alex's dimension on the page: ${shownOnline}`)
    await page.waitForWidget('hall', /Season 2/)
    await page.waitForWidget('hall', /Alex/)
    const frame = page.page.locator('[data-widget="map"] iframe')
    assert(await frame.count() === 1, 'the map is framed')
    assert((await frame.getAttribute('src')).startsWith(MAP), `the frame shows the map: ${await frame.getAttribute('src')}`)
    await page.page.frameLocator('[data-widget="map"] iframe').locator('#bluemap').waitFor({ timeout: 15 * SECOND })
    assert(await page.widget('leaderboards') === null, 'no leaderboards without a login')
    await ctx.join('Taylor')
    await page.waitForWidget('event-feed', /Taylor joined/)
    assert(!COORDINATES.test(await page.text()), `no coordinates anywhere on the page: ${await page.text()}`)

    // Alex's profile from the Hall: the summary only.
    await page.page.locator('[data-widget="hall"] a', { hasText: 'Alex' }).first().click()
    const summary = await page.waitForWidget('profile', /Season 2/)
    assert(/Alex/.test(summary) && !/Play time/i.test(summary) && !/Edge/.test(summary), `only Alex's summary: ${summary}`)
    page.check('the public dashboard')

    // Logged in by the code the page shows, typed in Minecraft by Alex.
    const before = page.responses.length
    await loginOnPage(ctx, page, alex)
    const full = await page.waitForWidget('profile', /Play time/i)
    await page.waitForWidget('profile', /Edge/)
    const gearShown = await page.widget('profile')
    assert(/Diamond Sword/.test(gearShown) && /Sharpness V\b/.test(gearShown), `Alex's sword as text: ${full}`)
    await page.goto('/')
    await page.waitForWidget('leaderboards', /Deaths/i)
    await page.waitForWidget('leaderboards', /Play time/i)
    // Every answer to a request that carried the session, whatever it asked for.
    const withSession = page.responses.slice(before).filter((r) => r.session && r.url.startsWith(origin + '/api/'))
    assert(withSession.length > 0, 'the page asked the API logged in')
    for (const r of withSession) {
      assert(r.headers['cache-control'] === 'private, no-store', `${r.url} logged in is private, no-store: ${r.headers['cache-control']}`)
    }
    page.check('the dashboard logged in')
  }
}
