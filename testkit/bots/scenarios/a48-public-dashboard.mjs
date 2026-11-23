// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A48. Without logging in, the dashboard shows the season clock, who's online, the event feed, the
// map and the Hall, but no leaderboards or full profiles; logged-in responses carry
// Cache-Control: private, no-store.
// M7 part 1 checks the API the dashboard reads; part 2 adds the page itself to this scenario.
import { SECOND, assert } from '../lib/context.mjs'
import { buildGallery } from '../lib/hall.mjs'
import { WebClient, assertPrivate, assertPublic, login, uuid, waitForEvent, webConfig } from '../lib/web.mjs'

const MAP = 'https://map.example.com'

export default {
  name: 'a48-public-dashboard',
  timeoutMs: 180 * SECOND,
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    await webConfig(ctx, admin, `links:\n  - { label: Map, url: "${MAP}", embed: frame }\n  - { label: Discord, url: "https://discord.gg/example" }\n`)
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await buildGallery(ctx, admin, { cells: 2 })
    await ctx.command(admin, '/give Alex minecraft:diamond_sword[custom_name="Edge",enchantments={sharpness:5}]', /^Gave 1 /)
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20 * SECOND)
    await ctx.eventually(async () => assert(await ctx.itemCount(admin, 'Alex') === 0, 'Alex is archived'))

    const visitor = new WebClient(ctx)

    // The season clock.
    const season = await visitor.get('/api/v1/season')
    assert(season.status === 200, `the season is public: ${season.status}`)
    assert(season.json.season === 2 && season.json.phase === 'intermission', `Season 2's Intermission: ${season.text}`)
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
  }
}
