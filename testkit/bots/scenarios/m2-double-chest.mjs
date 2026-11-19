// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A double chest never reaches across a cell's edge or the Hall's (questions 88). A chest placed
// outside the Hall can't join a Hall chest, nor can an owner join their chest to one outside their
// cell; and a double chest already across the Hall's edge (made here by /setblock) opens as a
// view-only copy from outside and feeds no hopper. A pair outside the Hall shows the halves really
// join and its hopper does pull.
import { SECOND, assert } from '../lib/context.mjs'
import { FLOOR, blockItems, buildGallery, cellOf, place } from '../lib/hall.mjs'
import { expectViewOnly, openContainer } from '../lib/protection.mjs'
import { standAt } from '../lib/visits.mjs'

// buildGallery's gallery with two cells spans x 20 to 38.
const EDGE = 38

export default {
  name: 'm2-double-chest',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Owner')
    const outsider = await ctx.join('Outsider')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    const cells = await buildGallery(ctx, admin, { cells: 2 })
    await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: /)
    const cell = await cellOf(ctx, admin, 'season-2', cells, 'Owner')
    const setblock = (pos, block) => ctx.command(admin, `/setblock ${pos.x} ${pos.y} ${pos.z} ${block}`, /^Changed the block/)
    const is = (pos, block) => ctx.holds(admin, `block ${pos.x} ${pos.y} ${pos.z} ${block}`)
    const z = 25

    // Facing north, a left half's other half is to its east, a right half's to its west.
    const hall = { x: EDGE, y: FLOOR, z }
    const out = { x: EDGE + 1, y: FLOOR, z }
    await setblock(hall, 'minecraft:chest[facing=north]')
    await standAt(ctx, admin, 'Outsider', { x: EDGE + 3, y: FLOOR, z })
    assert(!await place(ctx, admin, 'Outsider', out, 'minecraft:chest[facing=north,type=right]'), 'a chest outside can\'t join a Hall chest')
    assert(await is(out, 'minecraft:air'), 'it was taken back')
    assert(await place(ctx, admin, 'Outsider', out, 'minecraft:chest[facing=north]'), 'a chest beside it that joins nothing can be placed')
    await setblock(out, 'minecraft:air')

    // An owner's chest at their cell's edge can't join one in the corridor, but joins their own.
    const corridor = { x: cell.x1 + 1, y: FLOOR, z: cell.z0 + 3 }
    await setblock(corridor, 'minecraft:chest[facing=north]')
    assert(!await place(ctx, admin, 'Owner', { x: cell.x1, y: FLOOR, z: cell.z0 + 3 }, 'minecraft:chest[facing=north,type=left]'), 'an owner\'s chest can\'t join one outside their cell')
    await setblock({ x: cell.x0 + 2, y: FLOOR, z: cell.z0 + 3 }, 'minecraft:chest[facing=north]')
    assert(await place(ctx, admin, 'Owner', { x: cell.x0 + 1, y: FLOOR, z: cell.z0 + 3 }, 'minecraft:chest[facing=north,type=left]'), 'it joins one in their own cell')

    // A double chest already across the Hall's edge, and the same pair outside the Hall.
    const control = { x: 50, y: FLOOR, z }
    const controlRight = { x: 51, y: FLOOR, z }
    for (const [left, right] of [[hall, out], [control, controlRight]]) {
      await setblock(left, 'minecraft:chest[facing=north,type=left]')
      await setblock(right, 'minecraft:chest[facing=north,type=right]')
      await ctx.command(admin, `/item replace block ${left.x} ${left.y} ${left.z} container.0 with minecraft:diamond 4`, /^Replaced /)
      assert(await is(left, 'minecraft:chest[type=left]') && await is(right, 'minecraft:chest[type=right]'), 'the halves join')
    }
    const stocked = await blockItems(ctx, admin, hall)

    assert(await openContainer(ctx, admin, 'Outsider', out) === 'copy', 'its outside half opens as a copy')
    await expectViewOnly(ctx, admin, outsider, hall)

    await setblock({ ...out, y: FLOOR - 1 }, 'minecraft:hopper')
    await setblock({ ...controlRight, y: FLOOR - 1 }, 'minecraft:hopper')
    await ctx.eventually(async () => assert(/diamond/.test(await blockItems(ctx, admin, { ...controlRight, y: FLOOR - 1 }) ?? ''), 'the hopper under the pair outside the Hall pulls from its other half'), 10 * SECOND)
    // Hoppers need game ticks: three seconds of them, enough to move several items.
    await ctx.sync(admin, [], 60)
    assert(await blockItems(ctx, admin, hall) === stocked, 'the hopper under the outside half took nothing from the Hall half')
    assert(await blockItems(ctx, admin, { ...out, y: FLOOR - 1 }) === null, 'that hopper is empty')
  }
}
