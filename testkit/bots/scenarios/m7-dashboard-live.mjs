// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The dashboard's live widgets in a browser: the season clock counts down to the planned end and to
// a dated Homecoming by the server's clock (this browser's runs an hour fast), and shows the end's
// countdown when one starts; who's online with their dimension; the leaderboards with the viewer
// marked; the event feed with a season first, a phase change and an announce-zone entry; the links
// with Spotify's embed under its own link.
import { DAY, HOUR, MINUTE, SECOND, assert, sleep } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { dashboard, loginOnPage, openPage } from '../lib/browser.mjs'
import { middle, move, standAt } from '../lib/visits.mjs'
import { box, launchFrontier } from '../lib/zones.mjs'
import { WebClient } from '../lib/web.mjs'

const SEASON_FILE = 'world/dimensions/minecraft/overworld/seasons/seasons/3.yml'
const PLAYLIST = 'https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M'

export default {
  name: 'm7-dashboard-live',
  timeoutMs: 4 * MINUTE,
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    await ctx.join('Sam')
    const { origin } = await dashboard(ctx, admin, [
      'links:',
      `  - { label: Server playlist, url: "${PLAYLIST}", embed: spotify }`,
      '  - { label: Discord, url: "https://discord.gg/example" }',
      ''
    ].join('\n'))
    await launchFrontier(ctx, admin)

    // The end in 2 days 3½ hours, Homecoming on a date 5 days 6½ hours away.
    await ctx.command(admin, `/season schedule end ${ctx.isoIn(2 * DAY + 3 * HOUR + 30 * MINUTE)}`, /^Season 3 now ends at /)
    const homecoming = ctx.isoIn(5 * DAY + 6 * HOUR + 30 * MINUTE).replace(/\.\d+Z$/, 'Z')
    const api = new WebClient(ctx)
    // The schedule's own write to the season file can land after the reply, so the edit is made
    // again until Seasons has it.
    await ctx.eventually(async () => {
      const file = ctx.readFile(SEASON_FILE).replace(/homecoming:[^\n]*\n( +[^\n]*\n)*/, '') + `homecoming:\n  unlock: { date: "${homecoming}" }\n`
      ctx.writeFile(SEASON_FILE, file)
      await ctx.command(admin, '/season reload', /^Reloaded /)
      await sleep(1500)
      assert((await api.get('/api/v1/season')).json.plannedHomecoming, 'Homecoming planned')
    }, 30 * SECOND)

    // This browser's clock is an hour fast; the countdowns follow the server's.
    const page = await openPage(ctx, origin, '/', { offsetMs: HOUR })
    await page.waitForWidget('season', /Season 3/)
    await page.waitForWidget('season', /Frontier/i)
    await page.waitForWidget('season', /Ends in 2d 3h/)
    await page.waitForWidget('season', /Homecoming in 5d 6h/)
    const online = await page.waitForWidget('online', /Sam/)
    assert(/Alex/.test(online) && /Overworld/.test(online), `who's online: ${online}`)

    // Leaderboards need a login; Alex's own row is marked.
    await loginOnPage(ctx, page, alex)
    // Past the arrival immunity after launch, which a kill can't get through.
    await ctx.advance(admin, 6 * SECOND)
    await ctx.command(admin, '/kill Sam', /^Killed Sam/)
    const boards = await page.waitForWidget('leaderboards', /Deaths 1\. Sam 1\b/, 60 * SECOND)
    assert(/Play time/i.test(boards) && /Distance/i.test(boards), `the three boards: ${boards}`)
    const you = page.page.locator('[data-widget="leaderboards"] [data-you]')
    await you.first().waitFor({ timeout: 15 * SECOND })
    assert(/Alex/.test(await you.first().innerText()), 'Alex\'s own row is marked')

    // The feed: a first, a zone entry, a phase change.
    await ctx.command(admin, '/advancement grant Alex only minecraft:story/mine_stone', /^Granted the advancement/)
    await page.waitForWidget('event-feed', /Alex.*Stone Age/)
    await box(ctx, admin, 'market', { x: 300, y: FLOOR - 1, z: 300 }, { x: 320, y: FLOOR + 10, z: 320 })
    await ctx.command(admin, '/season zone mode market announce', /^Zone market is now announce while it is locked\.$/)
    await standAt(ctx, admin, 'Sam', { x: 297, y: FLOOR, z: 310 })
    await move(ctx, admin, 'Sam', middle({ x: 305, y: FLOOR, z: 310 }), 'walk')
    await page.waitForWidget('event-feed', /Sam entered market/)
    await ctx.command(admin, '/season end', /^Season 3 ends in 10 minutes/)
    await page.waitForWidget('season', /Ending in (9|10)m/, 70 * SECOND)
    await page.waitForWidget('event-feed', /Season 3 is ending/)
    await ctx.command(admin, '/season cancel', /./)

    // The links: Spotify's official embed, and each link as a link.
    const spotify = page.page.locator('[data-widget="links"] iframe')
    assert(await spotify.count() === 1, 'the playlist is embedded')
    assert((await spotify.getAttribute('src')) === 'https://open.spotify.com/embed/playlist/37i9dQZF1DXcBWIGoYBM5M', `Spotify's embed address: ${await spotify.getAttribute('src')}`)
    const discord = page.page.locator('[data-widget="links"] a', { hasText: 'Discord' })
    assert((await discord.getAttribute('href')) === 'https://discord.gg/example', 'the Discord link')
    assert(await page.page.locator('[data-widget="links"] a', { hasText: 'Server playlist' }).count() === 1, 'the playlist is a link too')
    page.check('the live dashboard')
  }
}
