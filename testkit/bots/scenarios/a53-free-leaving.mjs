// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A53. Outside the Intermission, a visitor who walks or falls out of the Hall stays where they
// land, with their game mode back and their saved spot dropped; during the Intermission they are
// brought back to the Hall spawn; with hof.sealed: always they return to their saved spot
// (questions 245). Walking and falling are the test plugin's moves, as in A27.
import { assert } from '../lib/context.mjs'
import { FLOOR, gameMode } from '../lib/hall.mjs'
import { HALL_SPAWN } from '../lib/hub.mjs'
import { HOME, bed, hallToVisit, hof, inGallery, middle, move, standAt, waitInHall, waitNear } from '../lib/visits.mjs'

const OUT = { x: 45.5, y: FLOOR, z: 25.5 }
const LEFT = /^You left the Hall of Fame\.$/

export default {
  name: 'a53-free-leaving',
  async run (ctx) {
    const admin = await ctx.admin()
    const visitor = await ctx.join('Visitor')
    await hallToVisit(ctx, admin, { sealed: null })
    await bed(ctx, admin, { x: HOME.x + 1, y: FLOOR, z: HOME.z })
    await ctx.command(admin, '/season config get hof.sealed', /^hof\.sealed = intermission$/)
    // A hole under the gallery's floor to fall through.
    await ctx.command(admin, `/fill 25 ${FLOOR - 3} 25 26 ${FLOOR - 1} 26 minecraft:air`, /^Successfully filled /)

    const visit = async () => {
      await standAt(ctx, admin, 'Visitor', HOME)
      await hof(ctx, visitor, '/hof', /^Welcome to the Hall of Fame\./)
      await waitInHall(ctx, admin, 'Visitor')
      await standAt(ctx, admin, 'Visitor', { x: 25, y: FLOOR, z: 21 })
      await waitInHall(ctx, admin, 'Visitor')
    }

    // Walking out: they stay out, in survival, and have no saved spot to go back to.
    await visit()
    let told = ctx.waitForMessages(visitor, LEFT)
    await move(ctx, admin, 'Visitor', OUT, 'walk')
    await told
    await ctx.eventually(async () => assert(await gameMode(ctx, admin, 'Visitor') === 0, 'back in survival after walking out'))
    await waitNear(ctx, admin, 'Visitor', OUT)
    await hof(ctx, visitor, '/hof leave', /^You aren't visiting the Hall of Fame\.$/)
    await waitNear(ctx, admin, 'Visitor', OUT)

    // Falling out through the floor: the same.
    await visit()
    told = ctx.waitForMessages(visitor, LEFT)
    await standAt(ctx, admin, 'Visitor', { x: 25, y: FLOOR, z: 25 })
    await move(ctx, admin, 'Visitor', { x: 25.5, y: FLOOR - 3, z: 25.5 }, 'fall')
    await told
    await ctx.eventually(async () => assert(await gameMode(ctx, admin, 'Visitor') === 0, 'back in survival after falling out'))
    assert(!inGallery(await ctx.position(admin, 'Visitor')), 'still out of the Hall')

    // hof.sealed: always returns them to their saved spot, as before.
    await ctx.command(admin, '/season config set hof.sealed always', /^Set hof\.sealed to always in config\.yml \(it was intermission\)\.$/)
    await visit()
    told = ctx.waitForMessages(visitor, /^You left the Hall of Fame, so you're back where you came from\.$/)
    await move(ctx, admin, 'Visitor', OUT, 'walk')
    await told
    await waitNear(ctx, admin, 'Visitor', middle(HOME))

    // During the Intermission everyone stays: walking out brings them back to the Hall spawn.
    await ctx.command(admin, '/season config set hof.sealed intermission', /^Set hof\.sealed to intermission in config\.yml/)
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
    // In their cell, where they may arrange it for a while, so in survival.
    await ctx.eventually(async () => assert(inGallery(await ctx.position(admin, 'Visitor')), 'the visitor is in the Hall'))
    await standAt(ctx, admin, 'Visitor', { x: 25, y: FLOOR, z: 21 })
    told = ctx.waitForMessages(visitor, /^Everyone stays in the Hall of Fame until the next season launches\.$/)
    await move(ctx, admin, 'Visitor', OUT, 'walk')
    await told
    await waitNear(ctx, admin, 'Visitor', middle(HALL_SPAWN))
  }
}
