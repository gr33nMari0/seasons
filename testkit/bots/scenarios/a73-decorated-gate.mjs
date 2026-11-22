// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A73. A gate plane over a decorated archway shuts by filling only its air and opens by clearing
// only the gate blocks, leaving the archway as built; a block with contents in the plane no longer
// stops it; a block of the gate's own kind in the open plane is reported by /hof hall check
// (questions 280). c1's doorway plane is x = 25, z 22 to 26, four blocks high.
import { assert } from '../lib/context.mjs'
import { FLOOR, blockItems, buildGallery } from '../lib/hall.mjs'
import { GALLERY_FILE, blockIs } from '../lib/wand.mjs'

const X = 25
const BARS = 'minecraft:iron_bars'
const STAIR = { x: X, y: FLOOR + 3, z: 22 }
const LANTERN = { x: X, y: FLOOR + 3, z: 24 }
const CHEST = { x: X, y: FLOOR, z: 22 }
const OPENING = [{ x: X, y: FLOOR, z: 24 }, { x: X, y: FLOOR + 2, z: 26 }, { x: X, y: FLOOR + 1, z: 23 }]

export default {
  name: 'a73-decorated-gate',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await buildGallery(ctx, admin, { cells: 2 })
    await ctx.command(admin, `/hof cell anchor season-2:c1 gate ${X} ${FLOOR} 22`, /^Added a gate anchor /)
    await ctx.command(admin, `/hof cell anchor season-2:c1 gate ${X} ${FLOOR + 3} 26`, /^Added a gate anchor /)
    ctx.writeFile(GALLERY_FILE(), ctx.readFile(GALLERY_FILE()).replace(/^cells:/m, 'access: gate\ncells:'))
    await ctx.command(admin, '/season reload', /^Reloaded /)
    // The archway: stairs, a lantern, and a chest with something in it, all in the plane.
    await ctx.command(admin, `/setblock ${STAIR.x} ${STAIR.y} ${STAIR.z} minecraft:stone_brick_stairs[half=top]`, /^Changed the block/)
    await ctx.command(admin, `/setblock ${LANTERN.x} ${LANTERN.y} ${LANTERN.z} minecraft:lantern[hanging=true]`, /^Changed the block/)
    await ctx.command(admin, `/setblock ${CHEST.x} ${CHEST.y} ${CHEST.z} minecraft:chest`, /^Changed the block/)
    await ctx.command(admin, `/item replace block ${CHEST.x} ${CHEST.y} ${CHEST.z} container.0 with minecraft:diamond 3`, /^Replaced /)

    // Shut: only the air becomes bars.
    await ctx.command(admin, '/hof cell gate season-2:c1 shut', /^The gate of season-2:c1 is shut\.$/)
    for (const at of OPENING) {
      await ctx.eventually(async () => assert(await blockIs(ctx, admin, at, BARS), `bars at ${JSON.stringify(at)}`))
    }
    assert(await blockIs(ctx, admin, STAIR, 'minecraft:stone_brick_stairs'), 'the stairs stay')
    assert(await blockIs(ctx, admin, LANTERN, 'minecraft:lantern'), 'the lantern stays')
    assert(await blockIs(ctx, admin, CHEST, 'minecraft:chest'), 'the chest stays')
    assert((await blockItems(ctx, admin, CHEST)).includes('diamond'), 'with its diamonds')

    // Open: only the bars go.
    await ctx.command(admin, '/hof cell gate season-2:c1 open', /^The gate of season-2:c1 is open\.$/)
    for (const at of OPENING) {
      await ctx.eventually(async () => assert(await blockIs(ctx, admin, at, 'minecraft:air'), `air at ${JSON.stringify(at)}`))
    }
    assert(await blockIs(ctx, admin, STAIR, 'minecraft:stone_brick_stairs'), 'the stairs still stay')
    assert(await blockIs(ctx, admin, LANTERN, 'minecraft:lantern'), 'the lantern still stays')

    // Bars as decoration in the open plane would go when it opens: the check says so.
    await ctx.command(admin, `/setblock ${X} ${FLOOR + 3} 23 ${BARS}`, /^Changed the block/)
    await ctx.command(admin, '/hof hall check', /^Cell season-2:c1's gate plane holds minecraft:iron_bars at 25, -57, 23, which opening the gate clears\.$/)
  }
}
