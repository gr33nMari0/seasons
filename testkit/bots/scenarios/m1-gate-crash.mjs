// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// A gate change is journalled (GATE_SET) before the blocks change. The server stops dead right
// after the entry is on disk, with the gate still shut; after the restart the gate is set to what
// the cell's state says, open since the window is, and nothing waits for recovery.
import { assert } from '../lib/context.mjs'
import { gateGallery, waitGate } from '../lib/gates.mjs'

export default {
  name: 'm1-gate-crash',
  sessions: [
    async (ctx) => {
      const admin = await ctx.admin()
      await ctx.join('Owner')
      await gateGallery(ctx, admin)
      await waitGate(ctx, admin, 'minecraft:iron_bars')
      await ctx.command(admin, '/hof cell assign season-2:c1 Owner', /^Assigned season-2:c1 to Owner for Season 2\.$/)
      await ctx.command(admin, '/scenario crash-at GATE_SET Owner', /^Armed crash point /)
      const gone = ctx.disconnected(admin)
      admin.chat('/season decorate open')
      await gone
      ctx.log('the server halted at GATE_SET')
    },
    async (ctx) => {
      const admin = await ctx.admin()
      await waitGate(ctx, admin, 'minecraft:air')
      const status = await ctx.command(admin, '/season status', /^Journal: /)
      assert(/, 0 waiting for recovery$/.test(status), `nothing waits for recovery: ${status}`)
    }
  ]
}
