// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// A player who logs in inside a blocked zone, as after an admin draws one around where they logged
// out, is sent to the season spawn and told why. One online when a zone is drawn around them is
// found by the sweep, with no event to give them away, and put back where it last saw them outside
// every blocked zone, or at the spawn if that was inside the camp. Where a player last stood outside
// is kept in their record (questions 177): one who walked into a zone with the bypass, then lost it
// to a restart, goes back to that spot, not the spawn.
import { SECOND, assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { standAt, waitNear } from '../lib/visits.mjs'
import { SPAWN, cylinder, forceload, launchFrontier, permit } from '../lib/zones.mjs'

const SENT = /^You were in the (camp|den), which is locked, so you're back at the spawn\.$/
const atSpawn = (at) => Math.abs(at.x - (SPAWN.x + 0.5)) <= 2 && Math.abs(at.z - (SPAWN.z + 0.5)) <= 2
const YARD = { x: 800, y: FLOOR, z: 800 }
const BY_THE_YARD = { x: YARD.x + 20, y: FLOOR, z: YARD.z }

export default {
  name: 'm3-login-inside',
  sessions: [session1, session2]
}

async function session1 (ctx) {
  const admin = await ctx.admin()
  const pat = await ctx.join('Pat')
  let quinn = await ctx.join('Quinn')
  await launchFrontier(ctx, admin)

  // Online, when a zone is drawn around them.
  const camp = { x: 400, y: FLOOR, z: -400 }
  await forceload(ctx, admin, camp, 30)
  await standAt(ctx, admin, 'Pat', camp)
  // Back where the sweep last saw them, the spawn they were teleported from; or, if it had
  // already seen them on this spot, now inside the camp, at the spawn. Either way, told why.
  const toldPat = ctx.waitForMessages(pat, /^(The camp is locked until Homecoming|You were in the camp, which is locked, so you're back at the spawn)\.$/, 10 * SECOND)
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

  // Rae stands by the yard, then walks in with the bypass, which the restart takes away.
  await ctx.join('Rae')
  await forceload(ctx, admin, YARD, 40)
  await cylinder(ctx, admin, 'yard', YARD, 10)
  await standAt(ctx, admin, 'Rae', BY_THE_YARD)
  await ctx.sync(admin, [], 20)
  await permit(ctx, admin, 'Rae', 'seasons.zone.bypass')
  await standAt(ctx, admin, 'Rae', YARD)
  await ctx.sync(admin, [], 20)
}

async function session2 (ctx) {
  const admin = await ctx.admin()
  await forceload(ctx, admin, YARD, 40)
  const rae = await ctx.join('Rae')
  await ctx.waitForMessages(rae, /^The yard is locked until Homecoming\.$/, 15 * SECOND)
  await waitNear(ctx, admin, 'Rae', { x: BY_THE_YARD.x + 0.5, y: FLOOR, z: BY_THE_YARD.z + 0.5 }, 1)
}
