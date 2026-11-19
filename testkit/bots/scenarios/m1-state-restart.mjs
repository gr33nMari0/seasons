// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// Season state survives a restart: the planned end and the reminders already sent are kept,
// and a countdown interrupted by the restart is cancelled rather than resumed.
import { DAY, SECOND, assert } from '../lib/context.mjs'

const ONE_DAY = /^Season 2 ends in 1 day\b/

export default {
  name: 'm1-state-restart',
  sessions: [
    async (ctx) => {
      const discord = await ctx.webhook()
      const admin = await ctx.admin()
      ctx.writeFile('plugins/Seasons/secrets.yml', `schema-version: 1\ndiscordWebhook: "${discord.url}"\n`)
      await ctx.command(admin, '/season reload', /^Reloaded /)
      await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
      await ctx.command(admin, `/season schedule end ${ctx.isoIn(DAY + 3 * SECOND)}`, /^Season 2 now ends at /)
      await discord.waitFor(ONE_DAY, 15 * SECOND)
      await ctx.command(admin, '/season end', /^Season 2 ends in 10 minutes\b/)
    },
    async (ctx) => {
      const discord = await ctx.webhook()
      const admin = await ctx.admin()
      await ctx.command(admin, '/season info', [/^Phase: Active, Open stage$/, /^Planned end: /])
      await ctx.command(admin, '/season status', /^The end countdown was interrupted by a restart at /)
      assert(ctx.bossBars(admin).length === 0, `no countdown after the restart, saw ${ctx.bossBars(admin)}`)
      const quiet = ctx.quiet(admin, ONE_DAY)
      await ctx.advance(admin, 8 * SECOND)
      await ctx.sync(admin)
      quiet.verify()
      assert(!discord.posts.some((p) => ONE_DAY.test(p.content ?? '')), 'the 1 day reminder was not sent again')
    }
  ]
}
