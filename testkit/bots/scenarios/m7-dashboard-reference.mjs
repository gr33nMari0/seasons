// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The dashboard's reference pages in a browser: the Hall of Fame browser by season, the museum with
// a trophy and its label, season history, the zones in force in plain words, shared waypoints with
// their Nether coordinates, and the coordinate converter, negative coordinates included.
import { MINUTE, SECOND, assert } from '../lib/context.mjs'
import { FLOOR, buildGallery } from '../lib/hall.mjs'
import { dashboard, openPage } from '../lib/browser.mjs'
import { writeMuseum } from '../lib/museum.mjs'
import { box, endSeason2 } from '../lib/zones.mjs'

export default {
  name: 'm7-dashboard-reference',
  timeoutMs: 4 * MINUTE,
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Alex')
    await ctx.join('Sam')
    const { origin } = await dashboard(ctx, admin)
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await buildGallery(ctx, admin, { cells: 3 })
    await ctx.command(admin, '/season marker add hubs Big Tree --at 50 -60 50', /^Added waypoint Big Tree to hubs /)
    await ctx.command(admin, '/season marker add hubs Far Camp --at -100 70 -9', /^Added waypoint Far Camp to hubs /)

    // A trophy in the museum, with a label.
    await writeMuseum(ctx, admin)
    await ctx.command(admin, '/give SeasonsAdmin minecraft:heavy_core', /^Gave 1 /)
    await ctx.command(admin, '/museum add museum:core The first core', /^Put /)

    await endSeason2(ctx, admin)
    await ctx.command(admin, '/season launch', /^Season 3 has begun!$/, 30 * SECOND)
    await box(ctx, admin, 'old-base', { x: 500, y: FLOOR - 1, z: 500 }, { x: 520, y: FLOOR + 10, z: 520 })

    // Season history: both seasons, the current one marked, each with its players.
    const page = await openPage(ctx, origin, '/history')
    const history = await page.waitForWidget('history', /Season 3/)
    assert(/Season 2/.test(history) && /now/i.test(history), `both seasons, Season 3 now: ${history}`)
    assert(/3 players/.test(history), `Season 2's players, the admin's bot among them: ${history}`)

    // The Hall browser: Season 2's gallery, each cell with its owner and a link to their archive.
    await page.page.locator('[data-widget="history"] a', { hasText: 'Season 2' }).first().click()
    const hall = await page.waitForWidget('hall', /Alex/)
    assert(/Sam/.test(hall) && /Season 2/.test(hall), `Season 2's cells: ${hall}`)
    assert(await page.page.locator('[data-widget="hall"] a', { hasText: 'Alex' }).count() >= 1, 'Alex links to their profile')

    // The museum.
    await page.goto('/museum')
    const museum = await page.waitForWidget('museum', /The first core/)
    assert(/Heavy Core/.test(museum) && /Season 2/.test(museum) && /SeasonsAdmin/.test(museum), `the trophy, its season and credit: ${museum}`)

    // The zones in force in plain words, and the waypoints with their Nether coordinates.
    await page.goto('/')
    const rules = await page.waitForWidget('rules', /old base/)
    assert(/Locked: nobody goes in/.test(rules) && /Homecoming comes when the group votes/.test(rules), `the zone's rule and Homecoming in words: ${rules}`)
    const waypoints = await page.waitForWidget('waypoints', /Far Camp/)
    assert(/Big Tree/.test(waypoints) && /50, -60, 50/.test(waypoints), `Big Tree: ${waypoints}`)
    assert(/Nether -13, -2/.test(waypoints) && /Nether 6, 6/.test(waypoints), `Nether coordinates by floor division: ${waypoints}`)

    // The converter, both ways.
    const converter = page.page.locator('[data-widget="converter"]')
    await converter.locator('[name="overworld-x"]').fill('-100')
    await converter.locator('[name="overworld-z"]').fill('-9')
    await ctx.eventually(async () => {
      assert(await converter.locator('[name="nether-x"]').inputValue() === '-13', 'Nether x')
      assert(await converter.locator('[name="nether-z"]').inputValue() === '-2', 'Nether z')
    })
    await converter.locator('[name="nether-x"]').fill('10')
    await converter.locator('[name="nether-z"]').fill('-3')
    await ctx.eventually(async () => {
      assert(await converter.locator('[name="overworld-x"]').inputValue() === '80', 'Overworld x')
      assert(await converter.locator('[name="overworld-z"]').inputValue() === '-24', 'Overworld z')
    })
    page.check('the reference pages')
  }
}
