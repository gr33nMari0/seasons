// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A32. The nether roof is reachable from day one: old-nether is drawn with --max-y 127, so y 128
// and up is open above the circle, while y 127 and below stays locked.
import { SECOND, assert } from '../lib/context.mjs'
import { move, near } from '../lib/visits.mjs'
import { LOCKED, launchFrontier, place } from '../lib/zones.mjs'

const NETHER = 'minecraft:the_nether'
const ROOF = 128

export default {
  name: 'a32-nether-roof',
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    await launchFrontier(ctx, admin)
    await ctx.command(admin, `/execute in ${NETHER} run forceload add -64 -64 64 64`, /^(Marked|No chunks were marked)/, 60_000) // generating them first
    await ctx.command(admin, `/execute in ${NETHER} run tp SeasonsAdmin 0.5 ${ROOF} 0.5`, /^Teleported /)
    await ctx.eventually(async () => assert((await ctx.entityData(admin, 'SeasonsAdmin', 'Dimension')) === `"${NETHER}"`, 'the admin is in the Nether'))
    await ctx.command(admin, '/season zone add old-nether cylinder 40 --max-y 127', /^Added zone old-nether: /)

    // On the roof, inside the circle: free to walk and build.
    await ctx.command(admin, `/execute in ${NETHER} run tp Pat 10.5 ${ROOF} 10.5`, /^Teleported /)
    await ctx.eventually(async () => assert((await ctx.entityData(admin, 'Pat', 'Dimension')) === `"${NETHER}"`, 'Pat is in the Nether'))
    const notStopped = ctx.quiet(pat, LOCKED('old nether'))
    await move(ctx, admin, 'Pat', { x: 14.5, y: ROOF, z: 10.5 }, 'walk')
    assert(await place(ctx, admin, 'Pat', { x: 15, y: ROOF, z: 12 }, 'minecraft:cobblestone'), 'Pat builds on the roof')
    // Two full rounds of the zone sweep, which would send Pat down if anything did.
    await ctx.sync(admin, [pat], 20)
    notStopped.verify()
    assert(near(await ctx.position(admin, 'Pat'), { x: 14.5, y: ROOF, z: 10.5 }, 1), 'Pat stays on the roof')

    // Below the roof, the old Nether is locked.
    const refused = ctx.waitForMessages(pat, LOCKED('old nether'), 10 * SECOND, 'the refusal below the roof')
    await move(ctx, admin, 'Pat', { x: 14.5, y: 120, z: 10.5 }, 'fall')
    await refused
    await ctx.sync(admin, [pat], 20)
    assert((await ctx.position(admin, 'Pat')).y >= ROOF, 'Pat is kept on the roof')
    assert(!await place(ctx, admin, 'Pat', { x: 15, y: 127, z: 13 }, 'minecraft:cobblestone'), 'nobody builds into the locked Nether from the roof')
  }
}
