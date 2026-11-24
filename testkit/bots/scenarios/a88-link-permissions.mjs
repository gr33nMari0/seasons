// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A88. A Hall link and a season-spawn link work for a plain player; a new link to a point is
// op-only by default; access permission lets a player granted seasons.link.use.<id> (or
// seasons.link.use.*) through and refuses one without; a custom node works the same; when played
// <season> refuses players of another season (M8 plan, Permissions).
import { FLOOR } from '../lib/hall.mjs'
import { FOLLOWED, WELCOME, go as goFrom, link, setBlock } from '../lib/links.mjs'
import { HOME, hallToVisit, middle, standAt, waitNear } from '../lib/visits.mjs'
import { permit } from '../lib/zones.mjs'

const S3 = { x: 300, y: FLOOR, z: 300 }
const STEPS = { x: 150, y: FLOOR, z: 150 }
const REFUSED = /^You can't use this link\.$/
// Each link's trigger, a lodestone beside HOME: a plain player takes a link where it is (questions 334).
const TRIGGERS = {
  'to-hall': { x: 102, y: FLOOR, z: 100 },
  'to-s3': { x: 100, y: FLOOR, z: 102 },
  'to-steps': { x: 98, y: FLOOR, z: 100 },
  'to-s3b': { x: 100, y: FLOOR, z: 98 }
}
const from = (id) => `from ${TRIGGERS[id].x} ${TRIGGERS[id].y} ${TRIGGERS[id].z}`

export default {
  name: 'a88-link-permissions',
  async run (ctx) {
    const admin = await ctx.admin()
    const visitor = await ctx.join('Visitor')
    await hallToVisit(ctx, admin, { sealed: null })
    await ctx.command(admin, '/forceload add 140 140 160 160', /^(Marked|No chunks were marked)/)
    await ctx.command(admin, '/forceload add 290 290 310 310', /^(Marked|No chunks were marked)/)
    await ctx.command(admin, `/season spawn set at ${S3.x} ${S3.y} ${S3.z}`, /^Season 3's spawn is now /)
    await ctx.command(admin, `/hof point add steps ${STEPS.x} ${STEPS.y} ${STEPS.z}`, /^Point steps is at /)
    for (const at of Object.values(TRIGGERS)) await setBlock(ctx, admin, at, 'minecraft:lodestone')
    /** /hof link go as the visitor, standing beside the link's trigger. */
    const go = async (c, bot, id, expect) => {
      await standAt(c, admin, 'Visitor', HOME)
      return goFrom(c, bot, id, expect)
    }
    await link(ctx, admin, `add to-hall ${from('to-hall')} to hall`, /^Link to-hall leads to the Hall spawn, /)
    await link(ctx, admin, `add to-s3 ${from('to-s3')} to season 3`, /^Link to-s3 leads to Season 3's spawn, /)
    await link(ctx, admin, `add to-steps ${from('to-steps')} to point steps`, /^Link to-steps leads to point steps, /)
    await link(ctx, admin, 'info to-steps', [/^Link to-steps at 98, -60, 100: to point steps$/, /^ taken by ops$/])

    // A plain player: the Hall and a season's spawn, but not the op-only link.
    await standAt(ctx, admin, 'Visitor', { x: 120, y: FLOOR, z: 120 })
    await goFrom(ctx, visitor, 'to-hall', /^Stand at one of link to-hall's triggers to take it\.$/)
    await go(ctx, visitor, 'to-hall', WELCOME)
    await go(ctx, visitor, 'to-s3', FOLLOWED)
    await waitNear(ctx, admin, 'Visitor', middle(S3))
    await go(ctx, visitor, 'to-steps', REFUSED)

    // By permission: the link's own node, then the wildcard for another.
    await link(ctx, admin, 'access to-steps permission', /^Link to-steps may be taken by holders of seasons\.link\.use\.to-steps\.$/)
    await go(ctx, visitor, 'to-steps', REFUSED)
    await permit(ctx, admin, 'Visitor', 'seasons.link.use.to-steps')
    await go(ctx, visitor, 'to-steps', FOLLOWED)
    await waitNear(ctx, admin, 'Visitor', middle(STEPS))
    await link(ctx, admin, `add to-s3b ${from('to-s3b')} to season 3`, /^Link to-s3b leads to /)
    await link(ctx, admin, 'access to-s3b permission', /^Link to-s3b may be taken by holders of seasons\.link\.use\.to-s3b\.$/)
    await go(ctx, visitor, 'to-s3b', REFUSED)
    await permit(ctx, admin, 'Visitor', 'seasons.link.use.*')
    await go(ctx, visitor, 'to-s3b', FOLLOWED)

    // A node of the server's own, as a LuckPerms group gives.
    await link(ctx, admin, 'access to-hall permission group.vip', /^Link to-hall may be taken by holders of group\.vip\.$/)
    await go(ctx, visitor, 'to-hall', REFUSED)
    await permit(ctx, admin, 'Visitor', 'group.vip')
    await go(ctx, visitor, 'to-hall', WELCOME)
    await go(ctx, visitor, 'to-s3', FOLLOWED)

    // A condition: only players of a season.
    await link(ctx, admin, 'when to-s3 played 9', /^Link to-s3 may be taken by players of Season 9\.$/)
    await go(ctx, visitor, 'to-s3', /^This link isn't open yet\.$/)
    await link(ctx, admin, 'when to-s3 played 2', /^Link to-s3 may be taken by players of Season 2\.$/)
    await standAt(ctx, admin, 'Visitor', HOME)
    await go(ctx, visitor, 'to-s3', FOLLOWED)
    await waitNear(ctx, admin, 'Visitor', middle(S3))
  }
}
