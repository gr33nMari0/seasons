// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// A curation of an imported record and /hof import undo never both have an item (review of #24).
// A curator's Take of the stand's torches stops dead at CURATE_REMOVED: the record no longer holds
// them and recovery waits for the curator. Until it settles, /hof import undo is refused, so the
// cell gets nothing back the record doesn't hold. Once the curator is back, the torches are in the
// record again, and the undo puts them back on the stand: exactly once.
import { assert } from '../lib/context.mjs'
import { choose, openCurate, pick } from '../lib/curate.mjs'
import { at, buildSeasonOneCell, selectorData } from '../lib/hall.mjs'
import { carried } from '../lib/vault.mjs'

const STAND = { x: 65, y: -60, z: 44 }
const BUSY = /^Undoing the import of cell owner stopped: its owner's record is busy: being archived, unarchived or curated, or a curation is waiting for recovery\.$/

export default {
  name: 'm4-curate-import-undo',
  sessions: [
    async (ctx) => {
      const admin = await ctx.admin()
      await ctx.join('Owner')
      const curator = await ctx.join('Curator')
      await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
      await buildSeasonOneCell(ctx, admin, { x: 60, z: 40, head: 'Owner', chests: 1 })
      await ctx.command(admin, '/hof gallery create season-1 1', /^Created gallery season-1 /)
      await ctx.command(admin, '/hof gallery bounds season-1 58 -61 38 76 -50 50', /^Gallery season-1 now spans /)
      await ctx.command(admin, '/hof cell new season-1 owner', /^Created cell owner /)
      await ctx.command(admin, '/hof cell bounds season-1:owner 59 -61 39 75 -52 49', /^Cell season-1:owner now spans /)
      await ctx.command(admin, '/hof import season-1', /^Imported 1 cell from season-1; 0 skipped\.$/, 20_000)
      // The import is on disk, as a halt keeps only what is saved.
      await ctx.command(admin, '/save-all flush', /^Saved the game/)

      await ctx.command(admin, '/scenario permit Curator seasons.curate.view', /^Permitted /)
      await ctx.command(admin, '/scenario permit Curator seasons.curate.take', /^Permitted /)
      await openCurate(ctx, curator, 'Owner', 1)
      await pick(ctx, curator, 'torch')
      await choose(ctx, curator, 'take')
      await ctx.command(admin, '/scenario crash-at CURATE_REMOVED Curator', /^Armed crash point /)
      const gone = ctx.disconnected(admin)
      curator.chat('Crash test')
      await gone
      ctx.log('the server halted at CURATE_REMOVED')
    },
    async (ctx) => {
      const admin = await ctx.admin()
      const stand = at('minecraft:armor_stand', STAND)
      await ctx.command(admin, '/season status', /^Journal: .*, 1 waiting for recovery$/)

      // The Take waits for the curator; the undo waits for the Take.
      await ctx.command(admin, '/hof import undo season-1', BUSY, 20_000)
      assert(!await ctx.holds(admin, `entity ${stand}`), 'the stand is not put back')
      assert((await ctx.archive(admin, 'Owner', 1)).present, 'the record is still there')

      // The curator is back: their saved inventory never had the torches, so the record has them.
      await ctx.join('Curator')
      await ctx.eventually(async () => ctx.command(admin, '/season status', /^Journal: .*, 0 waiting for recovery$/), 20_000)
      assert(await carried(ctx, admin, 'Curator', 'torch') === 0, 'the curator has no torches')

      await ctx.command(admin, '/hof import undo season-1', /^Put back 1 cell of season-1\.$/, 20_000)
      const gear = await ctx.eventually(async () => {
        const found = await selectorData(ctx, admin, stand, 'equipment')
        assert(found, 'the stand is back')
        return found
      })
      assert(/minecraft:torch/.test(gear), `the stand holds its torches again: ${gear}`)
      assert(!await ctx.holds(admin, 'entity @e[type=item]'), 'nothing was dropped')
    }
  ]
}
