// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A31. A player flies 40,000 blocks from spawn without being stopped: outside the lock there is no
// distance limit. The flight is the test plugin's client moves (docs/adr/0001), in long hops.
import { SECOND, assert, sleep } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { move, near } from '../lib/visits.mjs'
import { SPAWN, drawBase, launchFrontier } from '../lib/zones.mjs'

export default {
  name: 'a31-far-flight',
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    await launchFrontier(ctx, admin)
    await drawBase(ctx, admin)
    await ctx.command(admin, `/tp Pat ${SPAWN.x} ${SPAWN.y} ${SPAWN.z}`, /^Teleported /)

    const stopped = ctx.expectNoMessage(pat, /locked/, 20 * SECOND)
    for (const x of [SPAWN.x + 10_000, SPAWN.x + 20_000, SPAWN.x + 30_000, SPAWN.x + 40_000]) {
      const to = { x: x + 0.5, y: FLOOR + 20, z: SPAWN.z + 0.5 }
      await move(ctx, admin, 'Pat', to, 'fly')
      await sleep(SECOND)
      const at = await ctx.position(admin, 'Pat')
      assert(Math.abs(at.x - to.x) < 2, `Pat flew on to x ${x}, at ${JSON.stringify(at)}`)
    }
    await sleep(3 * SECOND)
    const at = await ctx.position(admin, 'Pat')
    assert(at.x - SPAWN.x >= 40_000, `Pat is 40,000 blocks from spawn, at ${JSON.stringify(at)}`)
    assert(!near(at, SPAWN, 100), 'nothing sent Pat back')
    await stopped
  }
}
