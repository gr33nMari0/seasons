// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A96. Two cells whose bounds were selected from the outside, walls, floor and roof included:
// setting the bounds warns, /hof hall check reports them, and the owner decorating can't break the
// walls, floor or roof (cells.protectShell) but can break what they placed themselves.
// /hof cell shrink previews the inside and confirms it, /hof undo puts it back, and /hof gallery
// shrink does every such cell at once (1.4.1 plan, H1).
import { assert } from '../lib/context.mjs'
import { FLOOR, breakBlock, place } from '../lib/hall.mjs'

const FACES = 'floor, roof, north, south, west, east'

/** A hollow stone room 7 × 8 × 7 from x, with a doorway in its north wall. */
async function room (ctx, admin, x) {
  await ctx.command(admin, `/fill ${x} ${FLOOR - 1} 100 ${x + 6} ${FLOOR + 6} 106 minecraft:stone hollow`, /^Successfully filled/)
  await ctx.command(admin, `/fill ${x + 3} ${FLOOR} 100 ${x + 3} ${FLOOR + 1} 100 minecraft:air`, /^Successfully filled/)
}

export default {
  name: 'a96-cell-walls',
  async run (ctx) {
    const admin = await ctx.admin()
    const owner = await ctx.join('Owner')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await ctx.command(admin, '/forceload add 96 96 120 112', /^(Marked|No chunks were marked)/)
    await room(ctx, admin, 100)
    await room(ctx, admin, 108)
    await ctx.command(admin, `/hof gallery create walled 2`, /^Created gallery walled for Season 2\.$/)
    await ctx.command(admin, `/hof gallery bounds walled 99 ${FLOOR - 1} 99 116 ${FLOOR + 10} 108`, /^Gallery walled now spans /)
    await ctx.command(admin, `/hof gallery entry walled 103 ${FLOOR} 99`, /^Gallery walled's entry is now at /)
    for (const [id, x] of [['c1', 100], ['c2', 108]]) {
      await ctx.command(admin, `/hof cell new walled ${id}`, new RegExp(`^Created cell ${id} in gallery walled\\.$`))
      // Selected from the outside: the warning comes with the reply.
      await ctx.command(admin, `/hof cell bounds walled:${id} ${x} ${FLOOR - 1} 100 ${x + 6} ${FLOOR + 6} 106`, [
        new RegExp(`^Cell walled:${id} now spans ${x}, -61, 100 to ${x + 6}, -54, 106\\.$`),
        new RegExp(`^Cell walled:${id}'s bounds look like they include its walls \\(${FACES}\\)\\. A cell's bounds are the space inside: run /hof cell shrink walled:${id}\\.$`)
      ])
      await ctx.command(admin, `/hof cell anchor walled:${id} figure ${x + 3} ${FLOOR} 104 180`, /^Added a figure anchor /)
    }
    await ctx.command(admin, '/hof hall check', [
      /^Hall of Fame check:$/,
      new RegExp(`^Cell walled:c1's bounds look like they include its walls \\(${FACES}\\): /hof cell shrink walled:c1\\.$`),
      new RegExp(`^Cell walled:c2's bounds look like they include its walls \\(${FACES}\\): /hof cell shrink walled:c2\\.$`)
    ])

    // The owner decorating can't break out, but builds and breaks inside as before.
    await ctx.command(admin, '/hof cell assign walled:c1 Owner', /^Assigned /)
    await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open/)
    for (const wall of [{ x: 106, y: FLOOR + 2, z: 103 }, { x: 103, y: FLOOR - 1, z: 103 }, { x: 103, y: FLOOR + 6, z: 103 }]) {
      const told = ctx.waitForMessages(owner, /^That's part of your cell's walls, floor or roof: only builders change it\.$/)
      assert(!await breakBlock(ctx, admin, 'Owner', wall), `the owner can't break ${JSON.stringify(wall)}`)
      await told
      assert(await ctx.holds(admin, `block ${wall.x} ${wall.y} ${wall.z} minecraft:stone`), 'the wall stands')
    }
    const mine = { x: 104, y: FLOOR, z: 102 }
    assert(await place(ctx, admin, 'Owner', mine, 'minecraft:oak_planks'), 'the owner places a block inside')
    assert(await breakBlock(ctx, admin, 'Owner', mine), 'and breaks it again')

    // One cell: previewed, confirmed, undone.
    await ctx.command(admin, '/hof cell shrink walled:c1', new RegExp(`^Shrinking walled:c1 to 101, -60, 101 to 105, -55, 105 \\(${FACES}\\): run /hof cell shrink confirm within 60 seconds, or /hof cell shrink cancel\\.$`))
    await ctx.command(admin, '/hof cell shrink confirm', /^Cell walled:c1 now spans 101, -60, 101 to 105, -55, 105\.$/)
    await ctx.command(admin, '/hof cell shrink walled:c1', /^Cell walled:c1's bounds don't look like they include its walls: add --all to shrink every face anyway\.$/)
    await ctx.command(admin, '/hof undo', /^Put .* back as it was before your last edit\.$/)
    await ctx.command(admin, '/hof hall check', new RegExp(`^Cell walled:c1's bounds look like they include its walls \\(${FACES}\\)`))

    // The whole gallery at once.
    await ctx.command(admin, '/hof gallery shrink walled', /^Shrinking 2 cells of walled \(c1, c2\): run \/hof gallery shrink confirm within 60 seconds, or \/hof gallery shrink cancel\.$/)
    await ctx.command(admin, '/hof gallery shrink confirm', /^Shrank 2 cells of walled\.$/)
    const quiet = ctx.quiet(admin, /look like they include its walls/)
    await ctx.command(admin, '/hof hall check', /^(All clear|\d+ things? to look at)\.$/)
    await ctx.sync(admin, [], 5)
    quiet.verify()
  }
}
