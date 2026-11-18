// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A player who logs in inside a blocked zone, as after an admin draws one around where they logged
// out, is sent to the season spawn and told why. One online when a zone is drawn around them is
// found by the sweep, with no event to give them away, and put back where it last saw them outside
// every blocked zone.
import { SECOND, assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { standAt } from '../lib/visits.mjs'
import { SPAWN, cylinder, forceload, launchFrontier } from '../lib/zones.mjs'

const SENT = /^You were in the (camp|den), which is locked, so you're back at the spawn\.$/
const atSpawn = (at) => Math.abs(at.x - (SPAWN.x + 0.5)) <= 2 && Math.abs(at.z - (SPAWN.z + 0.5)) <= 2

export default {
  name: 'm3-login-inside',
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    let quinn = await ctx.join('Quinn')
    await launchFrontier(ctx, admin)

    // Online, when a zone is drawn around them.
    const camp = { x: 400, y: FLOOR, z: -400 }
    await forceload(ctx, admin, camp, 30)
    await standAt(ctx, admin, 'Pat', camp)
    const toldPat = ctx.waitForMessages(pat, /^The camp is locked until Homecoming\.$/, 10 * SECOND)
    await cylinder(ctx, admin, 'camp', { x: camp.x + 3, y: FLOOR, z: camp.z }, 10)
    await toldPat
    await ctx.eventually(async () => {
      const at = await ctx.position(admin, 'Pat')
      assert(Math.hypot(at.x - (camp.x + 3), at.z - camp.z) > 10, `Pat is out of the camp, at ${JSON.stringify(at)}`)
    })

    // Logged out, when a zone is drawn around where they stood.
    const den = { x: -400, y: FLOOR, z: 400 }
    await forceload(ctx, admin, den, 30)
    await standAt(ctx, admin, 'Quinn', den)
    await ctx.leave(quinn)
    await cylinder(ctx, admin, 'den', den, 10)
    quinn = await ctx.join('Quinn')
    await ctx.waitForMessages(quinn, SENT, 15 * SECOND)
    await ctx.eventually(async () => assert(atSpawn(await ctx.position(admin, 'Quinn')), 'Quinn is at the spawn'))
  }
}
