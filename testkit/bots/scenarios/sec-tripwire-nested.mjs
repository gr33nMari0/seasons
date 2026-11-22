// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Security review M3. The tripwire looks inside what a player carries: a tagged archived copy in a
// bundle is taken out at the next sweep, the rest of the bundle kept. And an archived copy is never
// placed: a tagged shulker box can't be put down to unpack untagged items from it.
import { assert } from '../lib/context.mjs'
import { carried } from '../lib/vault.mjs'

export default {
  name: 'sec-tripwire-nested',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Pat')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)

    await ctx.command(admin, '/scenario tagged Pat bundle', /^Gave Pat a bundle holding a tagged copy$/)
    await ctx.command(admin, '/scenario clock +31s', /./)
    await ctx.eventually(async () => {
      const inventory = await ctx.entityData(admin, 'Pat', 'Inventory')
      assert(/minecraft:bundle/.test(inventory) && /minecraft:stick/.test(inventory), `the bundle and its stick stay: ${inventory}`)
      assert(!/minecraft:diamond/.test(inventory), `the tagged diamond is gone from the bundle: ${inventory}`)
    })

    await ctx.command(admin, '/scenario tagged Pat shulker', /^Gave Pat a tagged shulker box$/)
    const { x, z } = await ctx.position(admin, 'Pat')
    await ctx.command(admin, `/scenario place Pat ${Math.floor(x) + 2} -60 ${Math.floor(z)} minecraft:shulker_box held`, /^Placing minecraft:shulker_box was refused$/)
    assert(!await ctx.holds(admin, `block ${Math.floor(x) + 2} -60 ${Math.floor(z)} minecraft:shulker_box`), 'no shulker box was placed')
    assert(await carried(ctx, admin, 'Pat', 'shulker_box') === 0, 'the tagged shulker box is deleted')
  }
}
