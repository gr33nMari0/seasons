// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// Security review H3. An archive cut short after its vault page is sealed but before the clear
// finds the player's inventory empty after the restart: that is not taken as "already cleared",
// since Seasons never journalled the clear. The archive stays open for an admin, rather than being
// marked done while the items it copied are somewhere else.
import { assert } from '../lib/context.mjs'
import { giveGear } from '../lib/gear.mjs'

export default {
  name: 'sec-empty-after-crash',
  sessions: [
    async (ctx) => {
      const admin = await ctx.admin()
      const victim = await ctx.join('Casey')
      await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
      await giveGear(ctx, admin, 'Casey')
      // Once the vault page is sealed, Casey's inventory and ender chest are emptied and saved, as
      // if moved into a chest somewhere, and the server stops dead.
      await ctx.command(admin, '/scenario act-at VAULT_SEALED Casey empty crash', /^Armed empty at VAULT_SEALED for Casey/)
      const gone = ctx.disconnected(victim)
      admin.chat('/season end --now')
      await gone
    },
    async (ctx) => {
      const admin = await ctx.admin()
      const before = await ctx.archive(admin, 'Casey', 2)
      assert(before.pending === 1, `Casey's archive waits for recovery: ${JSON.stringify(before)}`)
      const refused = ctx.waitForMessages(admin, /^Casey's inventory is empty, but Seasons never cleared it for their Season 2 archive, so recovery stopped/, 20_000)
      await ctx.join('Casey')
      await refused
      const after = await ctx.archive(admin, 'Casey', 2)
      assert(after.archivedThrough < 2, `Casey isn't taken as archived: ${JSON.stringify(after)}`)
      assert(after.pending === 1, `the archive stays open: ${JSON.stringify(after)}`)
    }
  ]
}
