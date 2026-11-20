// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// @isolated the End's dragon fight, which the reset can't put back
// The damage each player has dealt the dragon survives a restart mid-fight: after it, a death with
// no player's hand in it still goes to whoever dealt the most.
import { SECOND } from '../lib/context.mjs'
import { launchFrontier } from '../lib/zones.mjs'
import { hurt, killWithNobody, toTheEnd, waitForDragon } from '../lib/dragon.mjs'

export default {
  name: 'm4-dragon-restart',
  sessions: [
    async (ctx) => {
      const admin = await ctx.admin()
      await ctx.join('Pat')
      await ctx.join('Sam')
      await launchFrontier(ctx, admin)
      await toTheEnd(ctx, admin, ['Pat', 'Sam'])
      await waitForDragon(ctx, admin)
      await hurt(ctx, admin, 'Pat', 200, 'melee')
      await hurt(ctx, admin, 'Sam', 200, 'melee')
      await hurt(ctx, admin, 'Sam', 200, 'melee')
      await ctx.sync(admin, [], 20)
    },
    async (ctx) => {
      const admin = await ctx.admin()
      const pat = await ctx.join('Pat')
      await ctx.join('Sam')
      await toTheEnd(ctx, admin, ['Pat', 'Sam'])
      await waitForDragon(ctx, admin)
      const slain = ctx.waitForMessages(pat, /^Sam slew the ender dragon!$/, 15 * SECOND, 'the broadcast')
      await killWithNobody(ctx, admin)
      await slain
    }
  ]
}
