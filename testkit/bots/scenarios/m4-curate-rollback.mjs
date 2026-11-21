// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A curation whose delivery fails is rolled back: Take with the curator's inventory full, and a
// museum plinth taken by someone else between picking it and giving the reason, both leave the
// item in the archive. Removing gear the figure wears redraws the figure without it. With
// curate.requireReason off, an action runs as soon as it is chosen, with "no reason given".
import { assert } from '../lib/context.mjs'
import { GEAR_ITEMS, archiveWithGear, choose, curate, figureGear, openCurate, pick, plinth, reason } from '../lib/curate.mjs'
import { writeMuseum } from '../lib/museum.mjs'
import { carried } from '../lib/vault.mjs'

export default {
  name: 'm4-curate-rollback',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Owner')
    const cells = await archiveWithGear(ctx, admin, ['Owner'])
    await writeMuseum(ctx, admin)
    await ctx.eventually(async () => assert(/minecraft:iron_helmet/.test(await figureGear(ctx, admin, cells.Owner) ?? ''), 'the figure wears the helmet'))

    // A full inventory: the shield stays in the archive.
    await ctx.command(admin, '/give SeasonsAdmin minecraft:stone 2304', /^Gave 2304 /)
    await curate(ctx, admin, 'Owner', 'shield', 'take', 'Testing', /^Your inventory is full, so 1 × minecraft:shield stays in the archive\.$/)
    assert((await ctx.archive(admin, 'Owner', 2)).items === GEAR_ITEMS, 'nothing left the archive')
    assert(await carried(ctx, admin, 'SeasonsAdmin', 'shield') === 0, 'and the curator has no shield')
    await ctx.command(admin, '/clear SeasonsAdmin', /./)

    // The plinth is taken while the reason is asked for.
    await openCurate(ctx, admin, 'Owner')
    await pick(ctx, admin, 'torch')
    await choose(ctx, admin, 'museum')
    await plinth(ctx, admin, 0)
    await ctx.command(admin, '/give SeasonsAdmin minecraft:heavy_core', /^Gave 1 /)
    await ctx.command(admin, '/museum add museum:core', /^Put minecraft:heavy_core in museum:core\.$/)
    await reason(ctx, admin, 'Too late', /^museum:core isn't free any more, so 32 × minecraft:torch stays in the archive\.$/)
    assert((await ctx.archive(admin, 'Owner', 2)).items === GEAR_ITEMS, 'the torches stayed')

    // Destroying the helmet the figure wears: the figure is drawn again without it.
    await curate(ctx, admin, 'Owner', 'iron_helmet', 'destroy', 'Worn out', /^Destroyed 1 × minecraft:iron_helmet\./)
    await ctx.eventually(async () => assert(!/minecraft:iron_helmet/.test(await figureGear(ctx, admin, cells.Owner) ?? 'none'), 'the figure no longer wears the helmet'))

    // Without a required reason, choosing is enough.
    await ctx.command(admin, '/season config set curate.requireReason false', /^Set curate\.requireReason to false\b/)
    await openCurate(ctx, admin, 'Owner')
    await pick(ctx, admin, 'shield')
    const done = ctx.waitForMessages(admin, /^Took 1 × minecraft:shield\.$/)
    await choose(ctx, admin, 'take', { asked: false })
    await done
    await ctx.command(admin, '/season audit Owner', /curate take Owner \(ok\): Season 2, offhand\.0, 1 × minecraft:shield to SeasonsAdmin's inventory; reason: no reason given/)
  }
}
