// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// A broken markers.yml at startup is left out, and nothing else is: the zones load and stay locked,
// so a broken waypoint file can never open the old world. /season status shows it, admins hear
// about it as they join, the marker commands refuse to touch it, and a reload fails until it is
// fixed, after which the waypoints are back.
import { SECOND, assert } from '../lib/context.mjs'
import { middle, move, standAt } from '../lib/visits.mjs'
import { INSIDE, LOCKED, OUTSIDE, drawBase, inBase, launchFrontier } from '../lib/zones.mjs'

const MARKERS = 'world/dimensions/minecraft/overworld/seasons/markers.yml'

export default {
  name: 'm3-markers-left-out',
  sessions: [
    async (ctx) => {
      const admin = await ctx.admin()
      await launchFrontier(ctx, admin)
      await drawBase(ctx, admin)
      await standAt(ctx, admin, admin.username, OUTSIDE)
      await ctx.command(admin, '/season marker add hubs Hub', /^Added waypoint Hub to hubs /)
      // A hand edit gone wrong, found at the next start: two names that differ only in case.
      ctx.writeFile(MARKERS, 'schema-version: 1\nsets:\n  hubs: { Hub: { pos: [1, 64, 1] }, HUB: { pos: [2, 64, 2] } }\n')
    },
    async (ctx) => {
      const admin = await ctx.admin()
      // Admins hear about it a few seconds after joining, by Seasons' clock.
      const notice = ctx.waitForMessages(admin, /^markers\.yml couldn't be loaded at startup/, 15_000, 'the notice as an admin joins')
      await ctx.advance(admin, 3 * SECOND)
      await notice

      // Only markers.yml is left out: the season files loaded, so the base is still locked.
      const defaults = ctx.quiet(admin, /^Config problem at startup/)
      await ctx.command(admin, '/season status', /^markers\.yml couldn't be loaded at startup, so it is left out and no shared waypoints are shown until it is fixed and \/season reload succeeds: markers\.yml, at sets\.hubs\.HUB: the set already has a waypoint by that name, ignoring case$/)
      await ctx.sync(admin)
      defaults.verify()
      await ctx.command(admin, '/season zone list', [/^Season 3's zones:$/, /^base: .*now block\.$/])
      const pat = await ctx.join('Pat')
      await standAt(ctx, admin, 'Pat', OUTSIDE)
      const told = ctx.waitForMessages(pat, LOCKED('base'), 10 * SECOND, 'the refusal')
      await move(ctx, admin, 'Pat', middle(INSIDE), 'walk')
      await told
      await ctx.sync(admin, [pat], 20)
      const at = await ctx.position(admin, 'Pat')
      assert(!inBase(at), `Pat stays out of the base, at ${JSON.stringify(at)}`)

      // The marker commands leave the file to the admin, and a reload fails until it is fixed.
      const leftOut = /^markers\.yml failed to load at startup, so the waypoints can't be changed by command\. Fix the file and run \/season reload\.$/
      await ctx.command(admin, '/season marker add hubs Spawn', leftOut)
      await ctx.command(admin, '/season marker remove hubs Hub', leftOut)
      await ctx.command(admin, '/season reload', /^Reload failed\./)

      ctx.writeFile(MARKERS, 'schema-version: 1\nsets:\n  hubs: { Hub: { pos: [1, 64, 1] } }\n')
      await ctx.command(admin, '/season reload', /^Reloaded /)
      const quiet = ctx.quiet(admin, /^markers\.yml/)
      await ctx.command(admin, '/season status', /^Journal: /)
      await ctx.sync(admin)
      quiet.verify()
      await ctx.command(admin, '/season marker remove hubs hub', /^Removed waypoint Hub from hubs\.$/)
    }
  ]
}
