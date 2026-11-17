// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A15 (M1 part 2's half; blocks placed in the Hall not counting comes with the Hall in part 3).
// Placing a block 30,000 blocks from spawn updates the player's furthest block, which
// /season status shows as the season's furthest from 0,0.
import vec3 from 'vec3'
import { SECOND, assert, waitUntil } from '../lib/context.mjs'

export default {
  name: 'a15-furthest-block',
  async run (ctx) {
    const admin = await ctx.admin()
    const builder = await ctx.join('Builder')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await ctx.command(admin, '/season status', /^No block placed yet this season\.$/)
    await ctx.command(admin, '/give Builder minecraft:stone 8', /^Gave 8/)

    await place(ctx, admin, builder, 5, 0)
    await ctx.eventually(() => ctx.command(admin, '/season status', /^Furthest block from 0,0 this season: 5 blocks, by Builder at 5, 0$/))

    await place(ctx, admin, builder, 30000, 0)
    await ctx.eventually(() => ctx.command(admin, '/season status', /^Furthest block from 0,0 this season: 30000 blocks, by Builder at 30000, 0$/))

    // A nearer block doesn't replace it.
    await place(ctx, admin, builder, 100, 0)
    await ctx.command(admin, '/season status', /^Furthest block from 0,0 this season: 30000 blocks, by Builder at 30000, 0$/)
  }
}

/**
 * Has the builder place stone on the ground at x, z. The admin teleports them beside the spot,
 * because the bots stand still (docs/adr/0001).
 */
async function place (ctx, admin, bot, x, z) {
  await ctx.command(admin, `/tp Builder ${x + 2} -60 ${z}`, /^Teleported /)
  await ctx.eventually(async () => {
    const at = await ctx.position(admin, 'Builder')
    assert(Math.round(at.x) === x + 2 || Math.floor(at.x) === x + 2, `Builder is beside ${x}, ${z}`)
  })
  const ground = vec3(x, -61, z)
  await waitUntil(() => bot.blockAt(ground)?.name === 'grass_block', 20 * SECOND, `the ground at ${x}, ${z} to load`)
  await bot.equip(bot.inventory.items().find((item) => item.name === 'stone'), 'hand')
  await bot.placeBlock(bot.blockAt(ground), vec3(0, 1, 0))
  assert(await ctx.holds(admin, `block ${x} -60 ${z} minecraft:stone`), `stone stands at ${x}, ${z}`)
}
