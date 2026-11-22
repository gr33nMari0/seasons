// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A61. During the Intermission an op, and a player granted seasons.hof.roam, walk out of the Hall,
// are teleported out by /tp, take a link to a season's spawn and use /hof leave, and stay out;
// outside they add a spawn candidate where they stand; a player without the node is brought back;
// launch moves the roamers to the new spawn with everyone (questions 263).
import { FLOOR } from '../lib/hall.mjs'
import { HALL_SPAWN } from '../lib/hub.mjs'
import { HOME, hallToVisit, inGallery, middle, move, standAt, waitNear } from '../lib/visits.mjs'
import { assert } from '../lib/context.mjs'

const OUT = { x: 45.5, y: FLOOR, z: 25.5 }
const SPAWN_3 = { x: 300, y: FLOOR, z: 300 }
const PLATE = { x: 12, y: FLOOR, z: 22 }
const IN_HALL = { x: 25, y: FLOOR, z: 21 }

export default {
  name: 'a61-intermission-roam',
  async run (ctx) {
    const admin = await ctx.admin()
    const me = ctx.adminName
    const visitor = await ctx.join('Visitor')
    const roamer = await ctx.join('Roamer')
    await hallToVisit(ctx, admin, { cells: 3, sealed: null })
    await ctx.command(admin, `/forceload add ${SPAWN_3.x - 16} ${SPAWN_3.z - 16} ${SPAWN_3.x + 16} ${SPAWN_3.z + 16}`, /^(Marked|No chunks were marked)/)
    await ctx.command(admin, `/season spawn set at ${SPAWN_3.x} ${SPAWN_3.y} ${SPAWN_3.z}`, /^Season 3's spawn is now /)
    await ctx.command(admin, `/scenario permit Roamer seasons.hof.roam`, /^Permitted Roamer seasons\.hof\.roam$/)
    // A link in the hub leading out to a spot.
    await ctx.command(admin, `/setblock ${PLATE.x} ${PLATE.y} ${PLATE.z} minecraft:stone_pressure_plate`, /^Changed the block/)
    await ctx.command(admin, `/hof link add out ${PLATE.x} ${PLATE.y} ${PLATE.z}`, /^Link out at /)
    await ctx.command(admin, `/hof link to out ${HOME.x} ${HOME.y} ${HOME.z}`, /^Link out now leads to /)

    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
    for (const name of ['Visitor', 'Roamer', me]) {
      await ctx.eventually(async () => assert(inGallery(await ctx.position(admin, name), 3), `${name} is in the Hall`))
    }

    // Without the node: walking out brings them back.
    await standAt(ctx, admin, 'Visitor', IN_HALL)
    let told = ctx.waitForMessages(visitor, /^Everyone stays in the Hall of Fame until the next season launches\.$/)
    await move(ctx, admin, 'Visitor', OUT, 'walk')
    await told
    await waitNear(ctx, admin, 'Visitor', middle(HALL_SPAWN))
    // Nor can they take the link out.
    told = ctx.waitForMessages(visitor, /^Links out of the Hall wait for the next season to launch\.$/)
    await ctx.command(admin, `/scenario use Visitor ${PLATE.x} ${PLATE.y} ${PLATE.z}`, /^Used /)
    await told

    // The roamer walks out and stays out.
    await standAt(ctx, admin, 'Roamer', IN_HALL)
    told = ctx.waitForMessages(roamer, /^You left the Hall of Fame\.$/)
    await move(ctx, admin, 'Roamer', OUT, 'walk')
    await told
    await waitNear(ctx, admin, 'Roamer', OUT)
    // Back in by /tp, then out along the link.
    await standAt(ctx, admin, 'Roamer', IN_HALL)
    await ctx.command(admin, `/scenario use Roamer ${PLATE.x} ${PLATE.y} ${PLATE.z}`, /^Used /)
    await waitNear(ctx, admin, 'Roamer', middle(HOME), 1)
    // Back in again, and /hof leave: no saved spot, so the season spawn, outside the Hall.
    await standAt(ctx, admin, 'Roamer', IN_HALL)
    await ctx.command(roamer, '/hof leave', /^There was no spot to send you back to, so you're at the season spawn\.$/)
    await ctx.eventually(async () => assert(!inGallery(await ctx.position(admin, 'Roamer'), 3), 'the roamer left'))

    // The op is moved out by /tp, stays out and adds a candidate where they stand.
    await standAt(ctx, admin, me, IN_HALL)
    await standAt(ctx, admin, me, HOME)
    await ctx.sync(admin, [], 20)
    assert(!inGallery(await ctx.position(admin, me), 3), 'the op stayed out')
    await ctx.command(admin, '/season spawn candidate add north', /^Added spawn candidate north for Season 3 at 100, -60, 100/)

    // Launch moves everyone, roamers too, to the new spawn.
    await ctx.command(admin, '/season launch', /Season 3 has begun/, 30_000)
    for (const name of ['Visitor', 'Roamer', me]) {
      await waitNear(ctx, admin, name, middle(SPAWN_3), 3)
    }
  }
}
