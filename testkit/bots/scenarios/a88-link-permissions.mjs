// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A88. A Hall link and a season-spawn link work for a plain player; a new link to a point is
// op-only by default; access permission lets a player granted seasons.link.use.<id> (or
// seasons.link.use.*) through and refuses one without; a custom node works the same; when played
// <season> refuses players of another season (M8 plan, Permissions).
import { FLOOR } from '../lib/hall.mjs'
import { FOLLOWED, WELCOME, go, link } from '../lib/links.mjs'
import { HOME, hallToVisit, middle, standAt, waitNear } from '../lib/visits.mjs'
import { permit } from '../lib/zones.mjs'

const S3 = { x: 300, y: FLOOR, z: 300 }
const STEPS = { x: 150, y: FLOOR, z: 150 }
const REFUSED = /^You can't use this link\.$/

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
    await link(ctx, admin, 'add to-hall to hall', /^Link to-hall leads to the Hall spawn, /)
    await link(ctx, admin, 'add to-s3 to season 3', /^Link to-s3 leads to Season 3's spawn, /)
    await link(ctx, admin, 'add to-steps to point steps', /^Link to-steps leads to point steps, /)
    await link(ctx, admin, 'info to-steps', [/^Link to-steps, with no trigger: to point steps$/, /^ taken by ops$/])

    // A plain player: the Hall and a season's spawn, but not the op-only link.
    await standAt(ctx, admin, 'Visitor', HOME)
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
    await link(ctx, admin, 'add to-s3b to season 3', /^Link to-s3b leads to /)
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
