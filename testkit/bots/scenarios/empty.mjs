// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// M0: the empty scenario. Proves the harness end to end: a player bot and the admin bot join
// a real server with Seasons installed, and Seasons answers an admin command. Nothing else.
import { assert } from '../lib/context.mjs'

export default {
  name: 'empty',
  async run (ctx) {
    const player = await ctx.join('Bot1')
    assert(player.entity, 'Bot1 has a player entity')

    const admin = await ctx.admin()
    await ctx.command(admin, '/season status', /^Seasons \S+ on Paper$/)
  }
}
