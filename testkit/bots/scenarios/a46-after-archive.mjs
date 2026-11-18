// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A46. After /season end, an owner can place and break blocks in their own cell for
// decorating.afterArchive (15 minutes at TheRanch; 20 seconds here), then the cell locks. Their
// neighbour's cell stays closed to them throughout.
import { SECOND, assert } from '../lib/context.mjs'
import { breakBlock, buildGallery, cellOf, gameMode, inside, place } from '../lib/hall.mjs'

export default {
  name: 'a46-after-archive',
  async run (ctx) {
    const admin = await ctx.admin()
    const owner = await ctx.join('Owner')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    ctx.writeFile('plugins/Seasons/config.yml', 'schema-version: 1\ndecorating:\n  afterArchive: 20s\n')
    await ctx.command(admin, '/season reload', /^Reloaded /)
    const cells = await buildGallery(ctx, admin, { cells: 3 })
    await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: /)
    const cell = await cellOf(ctx, admin, 'season-2', cells, 'Owner')
    const neighbour = cells.find((c) => c.id !== cell.id)

    const told = ctx.waitForMessages(owner, /^You have 20 seconds to arrange your cell before it locks\.$/, 20_000)
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
    await told
    // Archived players are sent to their own cell.
    await ctx.eventually(async () => assert(inside(cell, await ctx.position(admin, 'Owner')), 'the owner is in their cell'))
    await ctx.eventually(async () => assert(await gameMode(ctx, admin, 'Owner') === 0, 'the owner is in survival in their cell'))

    const block = { x: cell.x0 + 1, y: -60, z: cell.z0 + 4 }
    assert(await place(ctx, admin, 'Owner', block), 'the owner places a block in their cell')
    assert(await breakBlock(ctx, admin, 'Owner', block), 'the owner breaks it again')
    assert(await place(ctx, admin, 'Owner', block), 'and places it back')
    assert(!await place(ctx, admin, 'Owner', { x: neighbour.x0 + 1, y: -60, z: neighbour.z0 + 4 }), 'the neighbour\'s cell is closed to them')

    // Ten seconds on by Seasons' clock, the cell is still open; twenty, and it locks.
    const early = ctx.quiet(owner, /^Your cell is locked\.$/)
    await ctx.advance(admin, 10 * SECOND)
    await ctx.sync(admin, [owner])
    early.verify()
    assert(await place(ctx, admin, 'Owner', { x: cell.x0 + 2, y: -60, z: cell.z0 + 4 }), 'the cell stayed open for the time given')
    assert(await breakBlock(ctx, admin, 'Owner', { x: cell.x0 + 2, y: -60, z: cell.z0 + 4 }), 'and they can still break in it')
    const locked = ctx.waitForMessages(owner, /^Your cell is locked\.$/, 10_000)
    await ctx.advance(admin, 10 * SECOND)
    await locked
    await ctx.eventually(async () => assert(await gameMode(ctx, admin, 'Owner') === 2, 'the owner is in adventure once locked'))
    assert(!await breakBlock(ctx, admin, 'Owner', block), 'the locked cell refuses breaking')
    assert(!await place(ctx, admin, 'Owner', { x: cell.x0 + 1, y: -60, z: cell.z0 + 5 }), 'the locked cell refuses building')
    assert(await ctx.holds(admin, `block ${block.x} ${block.y} ${block.z} minecraft:stone`), 'what they built stays')
    await ctx.sync(admin, [owner])
  }
}
