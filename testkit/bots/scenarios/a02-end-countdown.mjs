// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A2. /season end starts a 10-minute boss-bar countdown; /season cancel during it changes
// nothing; --now skips it; a second /season end during a countdown is refused.
import { SECOND, assert, sleep } from '../lib/context.mjs'

export default {
  name: 'a02-end-countdown',
  async run (ctx) {
    const admin = await ctx.admin()
    const player = await ctx.join('Bot1')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)

    await ctx.command(admin, '/season end', /^Season 2 ends in 10 minutes\b/)
    await ctx.waitForBossBar(player, /^Season 2 ends in (10:00|9:[0-5]\d)$/)
    await ctx.command(admin, '/season end', /^Season 2 is already ending\b/)
    await ctx.command(admin, '/season end --now', /^Season 2 is already ending\b/)

    await ctx.command(admin, '/season cancel', /^The end of Season 2 was cancelled\b/)
    await ctx.waitForNoBossBar(player)
    await ctx.command(player, '/season info', /^Phase: Active, Open stage$/)

    // Skipping the countdown: straight to archiving, which ends in the Intermission.
    await ctx.command(admin, '/season end --now', /^Season 2 has ended\b/)
    await sleep(2 * SECOND)
    assert(ctx.bossBars(player).length === 0, `--now shows no countdown, saw ${ctx.bossBars(player)}`)
    await ctx.command(player, '/season info', /^Phase: Intermission$/)
    await ctx.command(admin, '/season end', /^Season 2 has already ended\b/)
    await ctx.command(admin, '/season cancel', /^There is no countdown to cancel\b/)
  }
}
