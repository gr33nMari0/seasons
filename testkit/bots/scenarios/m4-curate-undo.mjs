// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// Destroy and /hof curate undo: a destroyed helmet leaves the archive and the figure, waits in the
// recycle bin through a restart, and comes back to its slot and the figure with undo, audited; a
// second undo, an unknown id and an id 30 days old are refused, the last because the bin no longer
// keeps it.
import { DAY, assert } from '../lib/context.mjs'
import { GEAR_ITEMS, archiveWithGear, auditText, curate, figureGear } from '../lib/curate.mjs'
import { cellOf, layout } from '../lib/hall.mjs'

export default {
  name: 'm4-curate-undo',
  sessions: [
    async (ctx) => {
      const admin = await ctx.admin()
      await ctx.join('Owner')
      const cells = await archiveWithGear(ctx, admin, ['Owner'])
      await ctx.eventually(async () => assert(/minecraft:iron_helmet/.test(await figureGear(ctx, admin, cells.Owner) ?? ''), 'the figure wears the helmet'))
      await curate(ctx, admin, 'Owner', 'iron_helmet', 'destroy', 'Worn out', /^Destroyed 1 × minecraft:iron_helmet\. \/hof curate undo \S+ brings it back within 30 days\.$/)
      await curate(ctx, admin, 'Owner', 'dirt', 'destroy', 'Just dirt', /^Destroyed 64 × minecraft:dirt\./)
      assert((await ctx.archive(admin, 'Owner', 2)).items === GEAR_ITEMS - 65, 'both left the archive')
      await ctx.eventually(async () => assert(!/minecraft:iron_helmet/.test(await figureGear(ctx, admin, cells.Owner) ?? 'none'), 'the figure lost the helmet'))
    },
    async (ctx) => {
      const admin = await ctx.admin()
      const cell = await cellOf(ctx, admin, 'season-2', layout({ cells: 4 }), 'Owner')
      const audit = await auditText(ctx, admin, 'Owner')
      const helmet = /curate destroy Owner \(ok\): Season 2, armour\.3, 1 × minecraft:iron_helmet to the recycle bin as (\S+);/.exec(audit)?.[1]
      const dirt = /curate destroy Owner \(ok\): Season 2, storage\.1, 64 × minecraft:dirt to the recycle bin as (\S+);/.exec(audit)?.[1]
      assert(helmet && dirt, `the audit names both ids: ${audit}`)

      await ctx.command(admin, '/hof curate undo nothing-here', /^There is nothing in the recycle bin with id nothing-here\.$/)
      await ctx.command(admin, `/hof curate undo ${helmet}`, /^Put 1 × minecraft:iron_helmet back in Owner's Season 2 archive\.$/)
      assert((await ctx.archive(admin, 'Owner', 2)).items === GEAR_ITEMS - 64, 'the helmet is back')
      await ctx.eventually(async () => assert(/minecraft:iron_helmet/.test(await figureGear(ctx, admin, cell) ?? ''), 'the figure wears it again'))
      await ctx.command(admin, `/hof curate undo ${helmet}`, new RegExp(`^There is nothing in the recycle bin with id ${helmet}\\.$`))
      await ctx.command(admin, '/season audit Owner', /curate undo Owner \(ok\): Season 2, armour\.3, 1 × minecraft:iron_helmet back from the recycle bin/)

      // After 30 days the bin lets the dirt go.
      await ctx.advance(admin, 31 * DAY)
      await ctx.command(admin, `/hof curate undo ${dirt}`, new RegExp(`^There is nothing in the recycle bin with id ${dirt}\\.$`))
      assert((await ctx.archive(admin, 'Owner', 2)).items === GEAR_ITEMS - 64, 'the dirt stays gone')
    }
  ]
}
