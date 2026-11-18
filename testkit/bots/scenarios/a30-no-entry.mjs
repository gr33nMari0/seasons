// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A30. A player can't walk, fly, ride, pearl, chorus-teleport or portal into a locked base zone,
// and is put back at their last valid position with a message. Bots can't move themselves through
// the protocol translation (docs/adr/0001), so walking and flying are the test plugin's client
// moves, riding moves a boat with the player in it, chorus fruit and portals are teleports with
// the causes the game gives them, and a new nether portal is the game's pairing event (ADR 0003);
// the pearl is a real one. A holder of seasons.zone.bypass walks in.
import { SECOND, assert } from '../lib/context.mjs'
import { middle, move, waitNear } from '../lib/visits.mjs'
import { INSIDE, OUTSIDE, drawBase, expectEveryRouteRefused, inBase, launchFrontier, permit, portalPair } from '../lib/zones.mjs'

export default {
  name: 'a30-no-entry',
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    await launchFrontier(ctx, admin)
    await drawBase(ctx, admin)

    await expectEveryRouteRefused(ctx, admin, pat, OUTSIDE, INSIDE, 'base', (at) => !inBase(at))

    // A new nether portal the trip would make inside the base.
    const told = ctx.waitForMessages(pat, /^That portal leads into the base, which is locked until /, 10 * SECOND, 'the portal refusal')
    assert(!await portalPair(ctx, admin, 'Pat', 'minecraft:overworld', INSIDE), 'no portal is made inside the base')
    await told
    assert(!await ctx.holds(admin, `block ${INSIDE.x} ${INSIDE.y} ${INSIDE.z} minecraft:obsidian`), 'no obsidian frame inside the base')

    // Holders of seasons.zone.bypass are exempt.
    await permit(ctx, admin, 'Pat', 'seasons.zone.bypass')
    await move(ctx, admin, 'Pat', middle(INSIDE), 'walk')
    await ctx.sync(admin, [pat], 20)
    await waitNear(ctx, admin, 'Pat', middle(INSIDE))
  }
}
