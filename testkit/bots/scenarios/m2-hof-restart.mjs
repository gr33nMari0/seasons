// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// The saved spot survives logging out and a restart: a visitor who logged out inside the Hall
// stays there, and /hof leave still takes them back, in the game mode they left in.
import { assert } from '../lib/context.mjs'
import { FLOOR, gameMode } from '../lib/hall.mjs'
import { HOME, bed, hallToVisit, hof, inGallery, middle, standAt, waitInHall, waitNear } from '../lib/visits.mjs'

export default {
  name: 'm2-hof-restart',
  sessions: [
    async (ctx) => {
      const admin = await ctx.admin()
      const visitor = await ctx.join('Visitor')
      await hallToVisit(ctx, admin)
      await bed(ctx, admin, { x: HOME.x + 1, y: FLOOR, z: HOME.z })
      await standAt(ctx, admin, 'Visitor', HOME)
      await hof(ctx, visitor, '/hof', /^Welcome to the Hall of Fame\./)
      await waitInHall(ctx, admin, 'Visitor')

      await ctx.leave(visitor)
      await ctx.join('Visitor')
      await waitInHall(ctx, admin, 'Visitor')
    },
    async (ctx) => {
      const admin = await ctx.admin()
      const visitor = await ctx.join('Visitor')
      await waitInHall(ctx, admin, 'Visitor')
      assert(inGallery(await ctx.position(admin, 'Visitor')), 'still in the Hall after the restart')
      await ctx.command(visitor, '/hof leave', /^Back where you left\.$/)
      await waitNear(ctx, admin, 'Visitor', middle(HOME))
      await ctx.eventually(async () => assert(await gameMode(ctx, admin, 'Visitor') === 0, 'back in survival'))
    }
  ]
}
