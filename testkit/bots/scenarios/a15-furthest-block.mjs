// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A15 (M1 part 2's half; blocks placed in the Hall not counting comes with the Hall in part 3).
// Placing a block 30,000 blocks from spawn updates the player's furthest block, which
// /season status shows as the season's furthest from 0,0.
//
// TODO: place the block from the bot itself once Mineflayer speaks the server's version; through
// ViaBackwards the server ignores its placement (docs/adr/0001).
import { assert } from '../lib/context.mjs'

export default {
  name: 'a15-furthest-block',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Builder')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await ctx.command(admin, '/season status', /^No block placed yet this season\.$/)

    await place(ctx, admin, 5, 0)
    await ctx.eventually(() => ctx.command(admin, '/season status', /^Furthest block from 0,0 this season: 5 blocks, by Builder at 5, 0$/))

    await place(ctx, admin, 30000, 0)
    await ctx.eventually(() => ctx.command(admin, '/season status', /^Furthest block from 0,0 this season: 30000 blocks, by Builder at 30000, 0$/))

    // A nearer block doesn't replace it.
    await place(ctx, admin, 100, 0)
    await ctx.command(admin, '/season status', /^Furthest block from 0,0 this season: 30000 blocks, by Builder at 30000, 0$/)
  }
}

/**
 * Places stone at x, -60, z as the builder. Mineflayer's own block placing is refused through the
 * protocol translation (docs/adr/0001), so the test plugin fires the same BlockPlaceEvent the
 * server fires for a player and sets the block if nothing cancels it.
 */
async function place (ctx, admin, x, z) {
  await ctx.command(admin, `/scenario place Builder ${x} -60 ${z} minecraft:stone`, /^Placed minecraft:stone /)
  assert(await ctx.holds(admin, `block ${x} -60 ${z} minecraft:stone`), `stone stands at ${x}, ${z}`)
}
