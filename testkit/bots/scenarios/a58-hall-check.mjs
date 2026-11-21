// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A58. /hof hall check reports a wing that doesn't touch the hub, odd-sized cells, a gallery
// without an entry, cells without bounds or a figure and links leading nowhere, and says all is
// clear, with each gallery's cells and free cells, once they are fixed (questions 255).
import { FLOOR, buildGallery } from '../lib/hall.mjs'
import { addLink, markHub } from '../lib/hub.mjs'

export default {
  name: 'a58-hall-check',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await buildGallery(ctx, admin, { cells: 2 })

    // No hub yet.
    await ctx.command(admin, '/hof hall check', [/^Hall of Fame check:$/, /^No hub yet: mark one with \/hof hall hub\.$/, /^No Hall spawn yet: set one with \/hof hall spawn\.$/])
    await markHub(ctx, admin)

    // A second wing across a gap from the first, with no entry, an odd-sized cell, a cell with no
    // bounds and one with no figure; a link with nowhere to go.
    await ctx.command(admin, '/forceload add 50 20 70 31', /^(Marked|No chunks were marked)/)
    await ctx.command(admin, '/hof gallery create season-3 3', /^Created gallery season-3 for Season 3\.$/)
    await ctx.command(admin, `/hof gallery bounds season-3 50 ${FLOOR - 1} 20 70 ${FLOOR + 10} 30`, /^Gallery season-3 now spans /)
    for (const [id, x] of [['c1', 50], ['c2', 56], ['c3', 62]]) {
      await ctx.command(admin, `/hof cell new season-3 ${id}`, new RegExp(`^Created cell ${id} in gallery season-3\\.$`))
      await ctx.command(admin, `/hof cell bounds season-3:${id} ${x} ${FLOOR} 22 ${x + 4} ${FLOOR + 5} ${id === 'c3' ? 29 : 28}`, /^Cell season-3:\S+ now spans /)
      if (id !== 'c2') await ctx.command(admin, `/hof cell anchor season-3:${id} figure ${x + 2} ${FLOOR} 26`, /^Added a figure anchor /)
    }
    await ctx.command(admin, '/hof cell new season-3 c4', /^Created cell c4 in gallery season-3\.$/)
    await ctx.command(admin, `/setblock 10 ${FLOOR} 22 minecraft:lodestone`, /^Changed the block/)
    await addLink(ctx, admin, 'nowhere', { x: 10, y: FLOOR, z: 22 }, '')
    await addLink(ctx, admin, 'gone', { x: 12, y: FLOOR, z: 22 }, 'hall')
    await ctx.command(admin, '/hof hall check', [
      /^Hall of Fame check:$/,
      /^Gallery season-3 doesn't touch the hub or a gallery joined to it: walking there is leaving the Hall\.$/,
      /^Gallery season-3 has no entry\.$/,
      /^Cell season-3:c2 has no figure\.$/,
      /^Cell season-3:c3 is 5 × 6 × 8, where most of season-3's cells are 5 × 6 × 7\.$/,
      /^Cell season-3:c4 has no bounds\.$/,
      /^Cell season-3:c4 has no figure\.$/,
      /^Link nowhere leads nowhere yet\.$/,
      /^Link gone's block at 12, -60, 22 is air\.$/,
      /^season-2 \(Season 2\): 2 cells, 2 free\.$/,
      /^season-3 \(Season 3\): 4 cells, 4 free\.$/,
      /^8 things to look at\.$/
    ])

    // Fixed: the gap closed, an entry, every cell the same size with a figure, the links done.
    await ctx.command(admin, `/hof gallery bounds season-3 39 ${FLOOR - 1} 20 70 ${FLOOR + 10} 30`, /^Gallery season-3 now spans /)
    await ctx.command(admin, `/hof gallery entry season-3 40 ${FLOOR} 21`, /^Gallery season-3's entry is now at /)
    await ctx.command(admin, `/hof cell anchor season-3:c2 figure 58 ${FLOOR} 26`, /^Added a figure anchor /)
    await ctx.command(admin, `/hof cell bounds season-3:c3 62 ${FLOOR} 22 66 ${FLOOR + 5} 28`, /^Cell season-3:c3 now spans /)
    await ctx.command(admin, `/hof cell bounds season-3:c4 44 ${FLOOR} 22 48 ${FLOOR + 5} 28`, /^Cell season-3:c4 now spans /)
    await ctx.command(admin, `/hof cell anchor season-3:c4 figure 46 ${FLOOR} 26`, /^Added a figure anchor /)
    await ctx.command(admin, '/hof link remove nowhere', /^Removed link nowhere\.$/)
    await ctx.command(admin, `/setblock 12 ${FLOOR} 22 minecraft:stone_button[face=floor]`, /^Changed the block/)
    await ctx.command(admin, '/hof hall check', [
      /^Hall of Fame check:$/,
      /^season-2 \(Season 2\): 2 cells, 2 free\.$/,
      /^season-3 \(Season 3\): 4 cells, 4 free\.$/,
      /^All clear\.$/
    ])
  }
}
