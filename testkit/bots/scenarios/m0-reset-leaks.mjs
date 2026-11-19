// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The harness itself (docs/adr/0003): the reset between scenarios puts back blocks and removes
// entities even in a chunk that unloaded before it, and its leak check then finds a fresh boot.
import { assert } from '../lib/context.mjs'

const FAR = { x: 4000, y: -60, z: 4000 }
const BOX = `@e[type=!minecraft:player,x=${FAR.x},y=-64,z=${FAR.z},dx=15,dy=384,dz=15]`

export default {
  name: 'm0-reset-leaks',
  async run (ctx) {
    let admin = await ctx.admin()
    await ctx.command(admin, `/forceload add ${FAR.x} ${FAR.z}`, /^(Marked|No chunks were marked)/)
    await ctx.command(admin, `/summon minecraft:cow ${FAR.x + 0.5} ${FAR.y} ${FAR.z + 0.5} {NoAI:1b,Tags:["leak"]}`, /^Summoned /)
    await ctx.command(admin, `/setblock ${FAR.x + 2} ${FAR.y} ${FAR.z + 2} minecraft:chest`, /^Changed the block/)
    // Let the chunk unload, entities and all, before the reset.
    await ctx.command(admin, `/forceload remove ${FAR.x} ${FAR.z}`, /^(Unmarked|No chunks were removed)/)
    await ctx.eventually(async () => assert(!await ctx.holds(admin, `entity ${BOX}`), 'the cow unloaded with its chunk'), 30_000)

    const reply = await ctx.reset()
    assert(reply === 'ok', `the reset found a fresh boot: ${reply}`)

    admin = await ctx.admin()
    await ctx.command(admin, `/forceload add ${FAR.x} ${FAR.z}`, /^(Marked|No chunks were marked)/)
    await ctx.eventually(async () => assert(await ctx.holds(admin, `loaded ${FAR.x} ${FAR.y} ${FAR.z}`), 'the chunk loaded again'), 30_000)
    // Its entities load a moment after the chunk does.
    await ctx.sync(admin, [], 40)
    assert(!await ctx.holds(admin, `entity ${BOX}`), 'the cow is gone')
    assert(await ctx.holds(admin, `block ${FAR.x + 2} ${FAR.y} ${FAR.z + 2} minecraft:air`), 'the chest is gone')
  }
}
