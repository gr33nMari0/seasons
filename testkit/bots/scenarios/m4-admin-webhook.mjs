// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// The admin webhook gets the alerts: a reconciliation repair, and a recovery run (a /museum add
// stopped by a crash, settled when the op is back). None of them goes to the public channel.
// Without an admin webhook, alerts only reach the server log and online admins.
import { assert } from '../lib/context.mjs'
import { archiveWithGear } from '../lib/curate.mjs'
import { at } from '../lib/hall.mjs'
import { writeMuseum } from '../lib/museum.mjs'

const secrets = (discord, alerts) => `schema-version: 1\ndiscordWebhook: "${discord.url}"\nadminWebhook: "${alerts.url}"\n`

export default {
  name: 'm4-admin-webhook',
  sessions: [
    async (ctx) => {
      const admin = await ctx.admin()
      await ctx.join('Owner')
      const discord = await ctx.webhook()
      const alerts = await ctx.webhook({ path: 'admin' })
      ctx.writeFile('plugins/Seasons/secrets.yml', secrets(discord, alerts))
      await ctx.command(admin, '/season reload', /^Reloaded /)
      const cells = await archiveWithGear(ctx, admin, ['Owner'])

      // A reconciliation repair.
      const figure = at('minecraft:mannequin', cells.Owner.figure)
      await ctx.eventually(async () => assert(await ctx.holds(admin, `entity ${figure}`), 'the figure stands'))
      await ctx.command(admin, `/item replace entity ${figure} weapon.mainhand with minecraft:dirt`, /^Replaced /)
      await ctx.command(admin, '/hof reconcile season-2', /^Reconciled season-2: 1 repair\.$/, 20_000)
      await alerts.waitFor(new RegExp(`^Reconciliation repaired ${cells.Owner.key}: fixed the figure's gear$`), 20_000)
      assert(!discord.posts.some((p) => /Reconciliation/.test(p.content)), 'not on the public channel')

      // A /museum add stopped by a crash, for session 2's recovery.
      await writeMuseum(ctx, admin)
      await ctx.command(admin, '/clear SeasonsAdmin', /./)
      await ctx.command(admin, '/give SeasonsAdmin minecraft:heavy_core', /^Gave 1 /)
      await ctx.command(admin, '/scenario crash-at MUSEUM_STORED SeasonsAdmin', /^Armed crash point /)
      const gone = ctx.disconnected(admin)
      admin.chat('/museum add museum:core')
      await gone
    },
    async (ctx) => {
      // The receivers are up before the op comes back, and recovery with them.
      const discord = await ctx.webhook()
      const alerts = await ctx.webhook({ path: 'admin' })
      const admin = await ctx.admin()
      await alerts.waitFor(/^Journal recovery: museum for SeasonsAdmin, Season 2: finished$/, 20_000)
      assert(!discord.posts.some((p) => /Journal recovery/.test(p.content)), 'not on the public channel')

      // Without an admin webhook, nothing is posted for alerts.
      ctx.writeFile('plugins/Seasons/secrets.yml', `schema-version: 1\ndiscordWebhook: "${discord.url}"\n`)
      await ctx.command(admin, '/season reload', /^Reloaded /)
      const before = alerts.attempts.length
      await ctx.command(admin, '/hof reconcile season-2', /^Reconciled season-2: 0 repairs\.$/, 20_000)
      await ctx.command(admin, '/kill @e[type=minecraft:mannequin]', /^Killed /)
      await ctx.command(admin, '/hof reconcile season-2', /^Reconciled season-2: [1-9]\d* repairs?\.$/, 20_000)
      await ctx.sync(admin, [], 120)
      assert(alerts.attempts.length === before, 'no admin webhook, no alert posted')
      assert(!discord.posts.some((p) => /Reconciliation/.test(p.content)), 'and none on the public channel')
    }
  ]
}
