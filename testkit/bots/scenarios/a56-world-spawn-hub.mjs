// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A56. With the Hall spawn as the world spawn and launch.setWorldSpawn: false, launch keeps the
// world spawn; a first-time joiner and a player who respawns without a bed arrive in the hub in
// adventure mode, and /hof leave takes them to the season's spawn (questions 246, 247 and 258).
// This hub is TheRanch's size, 31 blocks square, so the game's spawn radius lands players in it.
import { assert } from '../lib/context.mjs'
import { FLOOR, buildGallery, gameMode } from '../lib/hall.mjs'
import { inBox, markHub } from '../lib/hub.mjs'
import { middle, standAt, waitNear } from '../lib/visits.mjs'
import { SPAWN, endSeason2, forceload } from '../lib/zones.mjs'

const CENTRE = { x: -60, y: FLOOR, z: 25 }
const HUB = { x1: CENTRE.x - 15, y1: FLOOR - 1, z1: CENTRE.z - 15, x2: CENTRE.x + 15, y2: FLOOR + 10, z2: CENTRE.z + 15 }

export default {
  name: 'a56-world-spawn-hub',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Dee')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await buildGallery(ctx, admin, { cells: 2 })
    await markHub(ctx, admin, { hub: HUB, spawn: CENTRE })
    await forceload(ctx, admin, CENTRE, 20)
    await ctx.command(admin, `/setworldspawn ${CENTRE.x} ${CENTRE.y} ${CENTRE.z}`, /^Set the world spawn point to /)
    await ctx.command(admin, '/season config set launch.setWorldSpawn false', /^Set launch\.setWorldSpawn to false in config\.yml \(it was true\)\.$/)

    // Launch keeps the world spawn at the Hall spawn.
    await endSeason2(ctx, admin)
    await ctx.command(admin, '/season launch', /^Season 3 has begun!$/, 30_000)
    await waitNear(ctx, admin, 'Dee', middle(SPAWN), 4)

    // A first-time joiner arrives in the hub, in adventure mode, and leaves for Season 3's spawn.
    const newbie = await ctx.join('Newbie')
    await ctx.eventually(async () => assert(inBox(HUB, await ctx.position(admin, 'Newbie')), 'Newbie joined in the hub'))
    await ctx.eventually(async () => assert(await gameMode(ctx, admin, 'Newbie') === 2, 'Newbie is in adventure mode in the hub'))
    await ctx.command(newbie, '/hof leave', /^There was no spot to send you back to, so you're at the season spawn\.$/)
    await waitNear(ctx, admin, 'Newbie', middle(SPAWN), 3)
    await ctx.eventually(async () => assert(await gameMode(ctx, admin, 'Newbie') === 0, 'Newbie is in survival at the spawn'))

    // Dee dies away from the spawn with no bed, respawns in the hub, and /hof leave takes her to
    // the season spawn, not back to where she died.
    const died = { x: SPAWN.x + 20, y: FLOOR, z: SPAWN.z }
    await forceload(ctx, admin, died, 4)
    await standAt(ctx, admin, 'Dee', died)
    await ctx.advance(admin, 6_000)
    const dee = ctx.bots.find((b) => b.username === 'Dee')
    const respawned = new Promise((resolve) => dee._client.once('respawn', resolve))
    await ctx.command(admin, '/kill Dee', /^Killed Dee/)
    await respawned
    await ctx.eventually(async () => assert(inBox(HUB, await ctx.position(admin, 'Dee')), 'Dee respawned in the hub'), 20_000)
    await ctx.eventually(async () => assert(await gameMode(ctx, admin, 'Dee') === 2, 'Dee is in adventure mode in the hub'))
    await ctx.advance(admin, 6_000)
    await ctx.command(dee, '/hof leave', /^There was no spot to send you back to, so you're at the season spawn\.$/)
    await waitNear(ctx, admin, 'Dee', middle(SPAWN), 3)
  }
}
