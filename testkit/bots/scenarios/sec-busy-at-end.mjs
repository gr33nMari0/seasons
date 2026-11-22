// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Security review H2. A player who is busy when the season's archive reaches them, such as in the
// middle of a vault withdrawal, isn't skipped: admins hear about it, and Seasons archives them as
// soon as they are free.
import { assert } from '../lib/context.mjs'
import { GEAR_ITEMS, expectEmpty, giveGear } from '../lib/gear.mjs'

export default {
  name: 'sec-busy-at-end',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Alex')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await giveGear(ctx, admin, 'Alex')
    await ctx.command(admin, '/scenario hold Alex', /^Holding Alex$/)

    const told = ctx.waitForMessages(admin, /^Alex was busy when Season 2's archive reached them, so they are held and archived as soon as they are free\.$/, 20_000)
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await told
    await ctx.waitForMessages(admin, /^Season 2 has ended\. Welcome to the Intermission\.$/, 20_000)
    assert((await ctx.archive(admin, 'Alex', 2)).archivedThrough < 2, 'Alex isn\'t archived while busy')

    await ctx.command(admin, '/scenario release Alex', /^Released Alex$/)
    await ctx.command(admin, '/scenario clock +15s', /./)
    await ctx.eventually(async () => {
      const archive = await ctx.archive(admin, 'Alex', 2)
      assert(archive.archivedThrough === 2 && archive.items === GEAR_ITEMS, `Alex is archived once free: ${JSON.stringify(archive)}`)
    }, 20_000)
    await expectEmpty(ctx, admin, 'Alex', assert)
  }
}
