// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// @isolated /season reset stops Seasons, which a shared server's reset between scenarios needs
// A76. /season reset is refused for a non-op holding every Seasons node and while a season is
// ending; for an op it warns with what would go, asks for /season reset confirm and then for DELETE
// typed in chat, and anything else cancels; once confirmed it writes a backup, closes every
// Seasons menu (so no view-only copy outlives Seasons), removes Seasons' displays, gives back the game modes it changed, deletes its world data (keeping the hub,
// galleries and cells with --keep-hall) and stops Seasons; after a restart /season init 1 works as
// on a fresh server (questions 283).
import { existsSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { assert } from '../lib/context.mjs'
import { FLOOR, gameMode } from '../lib/hall.mjs'
import { GALLERY_FILE, WORLD } from '../lib/wand.mjs'
import { openContainer } from '../lib/protection.mjs'
import { hallToVisit, standAt } from '../lib/visits.mjs'

export default {
  name: 'a76-reset',
  sessions: [
    async (ctx) => {
      const admin = await ctx.admin()
      const visitor = await ctx.join('Visitor')
      await hallToVisit(ctx, admin, { sealed: null })
      await ctx.command(admin, '/hof cell assign season-2:c1 Visitor', /^Assigned /)

      // Not for a player with every Seasons node, only for operators.
      for (const node of ['seasons.admin.season', 'seasons.admin.config', 'seasons.admin.hof']) {
        await ctx.command(admin, `/scenario permit Visitor ${node}`, /^Permitted /)
      }
      await ctx.command(visitor, '/season reset', /^Only a server operator can reset Seasons\.$/)

      // Not while a season is ending.
      await ctx.command(admin, '/season end', /^Season 2 ends in /)
      await ctx.command(admin, '/season reset', /^Seasons can't be reset while a season is ending/)
      await ctx.command(admin, '/season cancel', /^The end of Season 2 was cancelled/)

      // Anything but DELETE stops it.
      await ctx.command(admin, '/season reset --keep-hall', [/^This deletes Seasons from this world:/, /^That is \d+ player records, /, /^--keep-hall keeps the hub/, /^A backup is written first\. Run \/season reset confirm/])
      await ctx.command(admin, '/season reset confirm', [/^Last warning:/, /^Please type DELETE in chat/])
      await ctx.command(admin, 'nope', /^Reset cancelled; nothing was deleted\.$/)
      await ctx.command(admin, '/season reset confirm', /^No reset is waiting; run \/season reset first\.$/)

      // Something to clear away: the end, so the visitor has a figure; then in the hub, adventure.
      await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
      await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
      await ctx.eventually(async () => assert(await ctx.holds(admin, 'entity @e[type=mannequin]'), 'a figure is drawn'))
      await standAt(ctx, admin, 'Visitor', { x: 12, y: FLOOR, z: 25 })
      await ctx.eventually(async () => assert(await gameMode(ctx, admin, 'Visitor') === 2, 'the visitor is in adventure in the hub'))

      // A legacy chest of real items, open as a view-only copy while the reset runs.
      await ctx.command(admin, '/forceload add 80 10 110 40', /^(Marked|No chunks were marked)/)
      await ctx.command(admin, `/setblock 95 ${FLOOR} 25 minecraft:chest{Items:[{Slot:0b,id:"minecraft:diamond",count:3}]}`, /^Changed the block/)
      await ctx.command(admin, '/hof gallery create old-1 1 --legacy', /^Created gallery old-1 /)
      await ctx.command(admin, `/hof gallery bounds old-1 90 ${FLOOR - 1} 20 100 ${FLOOR + 10} 30`, /^Gallery old-1 now spans /)
      assert(await openContainer(ctx, admin, 'Visitor', { x: 95, y: FLOOR, z: 25 }) === 'copy', 'the visitor sees a copy')
      await ctx.eventually(async () => assert(visitor.currentWindow, 'the copy is open'))

      // DELETE: backup, menus closed, displays gone, game mode back, files gone but the layout,
      // Seasons stopped.
      await ctx.command(admin, '/season reset --keep-hall', /^A backup is written first/)
      await ctx.command(admin, '/season reset confirm', /^Please type DELETE in chat/)
      await ctx.command(admin, 'DELETE', [/^Backed up to plugins\/Seasons\/reset-backups\/.+\.zip\.$/, /^Seasons was reset and has stopped\./], 30_000)
      const backups = join(ctx.serverDir, 'plugins/Seasons/reset-backups')
      assert(readdirSync(backups).some((f) => f.endsWith('.zip')), 'a backup zip was written')
      await ctx.eventually(async () => assert(!visitor.currentWindow, 'the copy was closed before Seasons stopped'))
      assert(!existsSync(join(ctx.serverDir, 'plugins/Seasons/reset-pending')), 'the reset finished')
      assert(!await ctx.holds(admin, 'entity @e[type=mannequin]'), 'the figure is gone')
      await ctx.eventually(async () => assert(await gameMode(ctx, admin, 'Visitor') === 0, 'the visitor has survival back'))
      assert(!existsSync(join(ctx.serverDir, WORLD, 'data/season.json')), 'season.json is gone')
      assert(!existsSync(join(ctx.serverDir, WORLD, 'data/players')), 'the player records are gone')
      assert(existsSync(join(ctx.serverDir, WORLD, 'hall.yml')), 'the hub is kept')
      const gallery = ctx.readFile(GALLERY_FILE())
      assert(gallery.includes('c1:') && !gallery.includes('owners'), `the cells are kept, without owners: ${gallery}`)
    },
    async (ctx) => {
      // A fresh install: /season init works, and the kept cells are there.
      const admin = await ctx.admin()
      await ctx.command(admin, '/season init 1', /^Recorded Season 1 /)
      await ctx.command(admin, '/hof gallery info season-2', [/^Cell c1: free$/, /^Cell c2: free$/])
    }
  ]
}
