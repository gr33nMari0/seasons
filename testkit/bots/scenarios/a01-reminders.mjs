// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A1. With a planned end set, reminders arrive at 7 days, 1 day, 1 hour and 10 minutes, in chat
// and on Discord, and nothing ends at the planned time while autoEnd is false.
//
// Waiting days is impossible, so each step sets the planned end a few seconds beyond one
// threshold: only that reminder is still ahead, and it falls due a few seconds later.
import { DAY, HOUR, MINUTE, SECOND, assert } from '../lib/context.mjs'

const REMINDERS = [[7 * DAY, '7 days'], [DAY, '1 day'], [HOUR, '1 hour'], [10 * MINUTE, '10 minutes']]

export default {
  name: 'a01-reminders',
  async run (ctx) {
    const discord = await ctx.webhook()
    const admin = await ctx.admin()
    const player = await ctx.join('Bot1')
    ctx.writeFile('plugins/Seasons/secrets.yml', `schema-version: 1\ndiscordWebhook: "${discord.url}"\n`)
    await ctx.command(admin, '/season reload', /^Reloaded /)
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)

    for (const [threshold, label] of REMINDERS) {
      await ctx.command(admin, `/season schedule end ${ctx.isoIn(threshold + 4 * SECOND)}`, /^Season 2 now ends at /)
      const reminder = new RegExp(`^Season 2 ends in ${label}\\b`)
      const heard = ctx.waitForMessages(player, reminder, 15 * SECOND, `the ${label} reminder in chat`)
      await ctx.advance(admin, 4 * SECOND)
      await heard
      const post = await discord.waitFor(reminder, 15 * SECOND)
      assert(JSON.stringify(post.allowed_mentions) === '{"parse":[]}', `the ${label} post allows no mentions`)
    }
    const posted = discord.posts.map((p) => p.content)
    assert(posted.length === REMINDERS.length, `one post per reminder, got ${JSON.stringify(posted)}`)

    // The planned time passes with autoEnd false: no countdown, no archiving, still Active.
    await ctx.command(admin, `/season schedule end ${ctx.isoIn(3 * SECOND)}`, /^Season 2 now ends at /)
    await ctx.advance(admin, 8 * SECOND)
    await ctx.sync(admin, [player], 20)
    assert(ctx.bossBars(player).length === 0, `no countdown boss bar, saw ${ctx.bossBars(player)}`)
    await ctx.command(player, '/season info', /^Phase: Active, Open stage$/)
    assert(discord.posts.length === REMINDERS.length, 'nothing more was posted at the planned time')
  }
}
