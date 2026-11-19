// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A16, with A35's edge: before Homecoming, /pet add refuses the player's own pet standing inside a
// locked zone, as from just outside its edge nothing inside can be used; a pet just outside it can
// be registered. After Homecoming the pet inside can be registered too.
import { assert } from '../lib/context.mjs'
import { list, pet, register } from '../lib/pets.mjs'
import { BASE, EDGE, OUTSIDE, RADIUS, drawBase, launchFrontier } from '../lib/zones.mjs'

export default {
  name: 'a16-pet-zone-edge',
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    await launchFrontier(ctx, admin)
    await drawBase(ctx, admin)

    // Rex stands just inside the base's edge, 4 blocks from Pat outside it; Fido just outside.
    await pet(ctx, admin, 'horse', 'rex', { x: BASE.x + RADIUS - 1, y: OUTSIDE.y, z: BASE.z }, 'Pat', { name: 'Rex' })
    await pet(ctx, admin, 'wolf', 'fido', { x: OUTSIDE.x + 2, y: OUTSIDE.y, z: OUTSIDE.z + 2 }, 'Pat', { name: 'Fido' })
    await register(ctx, admin, pat, 'rex', EDGE('base'), OUTSIDE)
    await register(ctx, admin, pat, 'fido', /^Registered Fido \(wolf\) /, OUTSIDE)
    const [only] = await list(ctx, pat, 1)
    assert(/^- Fido /.test(only), `only Fido is registered: ${only}`)

    await ctx.command(admin, '/season unlock homecoming', /^Homecoming! /)
    await register(ctx, admin, pat, 'rex', /^Registered Rex \(horse\) /, OUTSIDE)
    await list(ctx, pat, 2)
  }
}
