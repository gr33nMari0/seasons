// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A27. Leaving the Hall by walking, pearl, pushing or falling returns the player to their saved
// spot. Bots can't move themselves through the protocol translation (docs/adr/0001), so walking,
// being pushed and falling are moves the test plugin makes as the client would: PlayerMoveEvent,
// and no teleport event any plugin sees. The server sees all three the same way, so they differ
// only in where the player goes. Stepping out and straight back in the same tick is caught only by
// the move event, not by the sweep, so it shows PlayerMoveEvent reaches Seasons. The pearl is a
// real one.
import { assert } from '../lib/context.mjs'
import { FLOOR, gameMode } from '../lib/hall.mjs'
import { HOME, bed, hallToVisit, hof, middle, move, pearl, standAt, waitInHall, waitNear } from '../lib/visits.mjs'

export default {
  name: 'a27-sealing',
  async run (ctx) {
    const admin = await ctx.admin()
    const visitor = await ctx.join('Visitor')
    await hallToVisit(ctx, admin)
    await bed(ctx, admin, { x: HOME.x + 1, y: FLOOR, z: HOME.z })
    await standAt(ctx, admin, 'Visitor', HOME)
    // A hole under the gallery's floor to fall through.
    await ctx.command(admin, `/fill 25 ${FLOOR - 3} 25 26 ${FLOOR - 1} 26 minecraft:air`, /^Successfully filled /)

    const routes = [
      ['walking', (name) => move(ctx, admin, name, { x: 45.5, y: FLOOR, z: 25.5 }, 'walk')],
      ['pushing', (name) => move(ctx, admin, name, { x: 25.5, y: FLOOR, z: 33.5 }, 'push')],
      ['falling', (name) => move(ctx, admin, name, { x: 25.5, y: FLOOR - 3, z: 25.5 }, 'fall')],
      ['stepping out and straight back', (name) => move(ctx, admin, name, { x: 45.5, y: FLOOR, z: 25.5 }, 'walk', { back: true })],
      ['pearl', (name) => pearl(ctx, admin, name, { x: 60, y: FLOOR, z: 25 })]
    ]
    for (const [route, leave] of routes) {
      await hof(ctx, visitor, '/hof', /^Welcome to the Hall of Fame\./)
      await waitInHall(ctx, admin, 'Visitor')
      await standAt(ctx, admin, 'Visitor', { x: 25, y: FLOOR, z: 25 })
      await waitInHall(ctx, admin, 'Visitor')
      const told = ctx.waitForMessages(visitor, /^You left the Hall of Fame, so you're back where you came from\.$/, 15_000, `the ${route} return`)
      await leave('Visitor')
      await told
      await waitNear(ctx, admin, 'Visitor', middle(HOME))
      assert(await gameMode(ctx, admin, 'Visitor') === 0, `back in survival after ${route}`)
    }
  }
}
