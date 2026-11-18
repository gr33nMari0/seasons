// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A29. A creeper standing at the saved spot sends the player to their bed instead.
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { HOME, bed, hallToVisit, hof, near, standAt, waitInHall } from '../lib/visits.mjs'

const BED = { x: HOME.x + 20, y: FLOOR, z: HOME.z }

export default {
  name: 'a29-creeper',
  async run (ctx) {
    const admin = await ctx.admin()
    const visitor = await ctx.join('Visitor')
    await hallToVisit(ctx, admin)
    await bed(ctx, admin, { x: HOME.x + 1, y: FLOOR, z: HOME.z })
    await bed(ctx, admin, BED)
    await ctx.command(admin, `/spawnpoint Visitor ${BED.x} ${BED.y} ${BED.z}`, /^Set spawn point to /)
    await standAt(ctx, admin, 'Visitor', HOME)
    await hof(ctx, visitor, '/hof', /^Welcome to the Hall of Fame\./)
    await waitInHall(ctx, admin, 'Visitor')

    await ctx.command(admin, `/summon minecraft:creeper ${HOME.x + 0.5} ${FLOOR} ${HOME.z + 0.5} {NoAI:1b}`, /^Summoned /)
    await hof(ctx, visitor, '/hof leave', /^Your spot wasn't safe \(monsters nearby\), so you're at your bed\.$/)
    await ctx.eventually(async () => assert(near(await ctx.position(admin, 'Visitor'), { x: BED.x + 0.5, y: FLOOR, z: BED.z + 0.5 }, 3), 'at their bed'))
  }
}
