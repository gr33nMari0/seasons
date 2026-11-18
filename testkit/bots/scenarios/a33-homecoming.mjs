// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// A33. /season unlock homecoming switches every zone to its afterUnlock mode in one tick (off by
// default, so players can build in old bases), with a title, a broadcast and a Discord post, and
// fireworks at the spawn; a zone locked again on its own before it opens with the rest. It happens
// once: a second unlock is refused, and a restart neither
// relocks anything nor repeats the fanfare. That every zone switches in the same tick is a unit
// test (ZonesTest); here /season zone list shows each zone's mode in force.
import { SECOND, assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { BASE, box, drawBase, launchFrontier, waitForTitle, zoneList } from '../lib/zones.mjs'

const FARM = { from: { x: 800, y: FLOOR - 1, z: 800 }, to: { x: 810, y: FLOOR + 10, z: 810 } }

export default {
  name: 'a33-homecoming',
  sessions: [
    async (ctx) => {
      const admin = await ctx.admin()
      const pat = await ctx.join('Pat')
      const discord = await ctx.webhook()
      ctx.writeFile('plugins/Seasons/secrets.yml', `schema-version: 1\ndiscordWebhook: "${discord.url}"\n`)
      await ctx.command(admin, '/season reload', /^Reloaded /)
      await launchFrontier(ctx, admin)
      await drawBase(ctx, admin)
      await box(ctx, admin, 'farm', FARM.from, FARM.to)
      await ctx.command(admin, '/season zone after farm no-build', /^Zone farm becomes no-build once it unlocks\.$/)

      // The base, unlocked and locked again on its own, still opens at Homecoming.
      await ctx.command(admin, '/season unlock base', /^Zone base is unlocked: it is off now\.$/)
      await ctx.command(admin, '/season lock base', /^Zone base is locked again: it is block now\.$/)
      let zones = await zoneList(ctx, admin, ['base', 'farm'])
      assert(/now block\.$/.test(zones.base) && /now block\.$/.test(zones.farm), `both zones are locked: ${JSON.stringify(zones)}`)

      const title = waitForTitle(pat, /Homecoming/)
      const broadcast = ctx.waitForMessages(pat, /^Homecoming! The old world is open/)
      await ctx.command(admin, '/season unlock homecoming', /^Homecoming! Every zone is now in its after-unlock mode\.$/)
      assert(await ctx.holds(admin, 'entity @e[type=minecraft:firework_rocket]'), 'Homecoming fireworks are in the air')
      await title
      await broadcast
      await discord.waitFor(/^Homecoming! The old world is open/)

      zones = await zoneList(ctx, admin, ['base', 'farm'])
      assert(/now off\.$/.test(zones.base), `the base is off: ${zones.base}`)
      assert(/now no-build\.$/.test(zones.farm), `the farm is no-build: ${zones.farm}`)
      await ctx.command(admin, '/season info', /^Phase: Active, Open stage$/)
      await ctx.command(admin, '/season unlock homecoming', /^Homecoming only happens once, in the Frontier stage\.$/)
      // The base is open: Pat walks in.
      await ctx.command(admin, `/tp Pat ${BASE.x + 0.5} ${BASE.y} ${BASE.z + 0.5}`, /^Teleported /)
    },
    async (ctx) => {
      const admin = await ctx.admin()
      const pat = await ctx.join('Pat')
      const quiet = ctx.expectNoMessage(pat, /Homecoming/, 5 * SECOND)
      const zones = await zoneList(ctx, admin, ['base', 'farm'])
      assert(/now off\.$/.test(zones.base) && /now no-build\.$/.test(zones.farm), `the zones stay unlocked: ${JSON.stringify(zones)}`)
      await ctx.command(admin, '/season info', /^Phase: Active, Open stage$/)
      await quiet
    }
  ]
}
