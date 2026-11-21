// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A57. A gate plane in a cell's doorway, touching its bounds, is accepted, and the cell's bounds can
// still be set with it there; a gate corner that doesn't touch the cell is refused, and so is a
// hand-edited one on reload (questions 250). c1 spans x 20 to 24; its doorway is the plane x = 25.
import { assert } from '../lib/context.mjs'
import { FLOOR, buildGallery } from '../lib/hall.mjs'
import { GALLERY_FILE, cellInFile, giveWand, select } from '../lib/wand.mjs'

export default {
  name: 'a57-doorway-gate',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    const [c1] = await buildGallery(ctx, admin, { cells: 2 })

    // The doorway, beside the cell's east side: accepted.
    await ctx.command(admin, `/hof cell anchor season-2:c1 gate 25 ${FLOOR} 23`, /^Added a gate anchor to season-2:c1 at 25, -60, 23\.$/)
    await ctx.command(admin, `/hof cell anchor season-2:c1 gate 25 ${FLOOR + 1} 25`, /^Added a gate anchor to season-2:c1 at 25, -59, 25\.$/)
    assert(cellInFile(ctx, 'c1').anchors.filter((a) => a.type === 'gate').length === 2, 'both corners are in the file')

    // Setting the cell's bounds again, by command and by wand, keeps the doorway gate.
    await ctx.command(admin, `/hof cell bounds season-2:c1 ${c1.x0} ${FLOOR - 1} ${c1.z0} ${c1.x1} ${FLOOR + 5} ${c1.z1}`, /^Cell season-2:c1 now spans 20, -61, 22 to 24, -55, 30\.$/)
    await giveWand(ctx, admin)
    await select(ctx, admin, admin, { x: c1.x0, y: FLOOR - 1, z: c1.z0 }, { x: c1.x1, y: FLOOR + 6, z: c1.z1 })
    await ctx.command(admin, '/hof cell bounds season-2:c1', /^Cell season-2:c1 now spans 20, -61, 22 to 24, -54, 30\.$/)

    // Bounds that would leave the doorway two blocks away are refused, as for any anchor.
    await ctx.command(admin, `/hof cell bounds season-2:c1 ${c1.x0} ${FLOOR - 1} ${c1.z0} ${c1.x1 - 1} ${FLOOR + 6} ${c1.z1}`, /^Cell season-2:c1's gate anchor at 25, -60, 23 would be outside those bounds\. Move or remove it first\.$/)

    // A corner that doesn't touch the cell is refused.
    await ctx.command(admin, `/hof cell anchor season-2:c2 gate 33 ${FLOOR} 23`, /^A gate corner goes inside cell season-2:c2 or right beside it\.$/)

    // A hand edit putting one there doesn't load.
    const file = ctx.readFile(GALLERY_FILE())
    const stray = file.replace(/(type: gate\n\s+pos:\n\s+- )25(\n\s+- -60\n\s+- 23)/, '$128$2')
    assert(stray !== file, 'the edit moved a gate corner')
    ctx.writeFile(GALLERY_FILE(), stray)
    await ctx.command(admin, '/season reload', [/^Reload failed/, /galleries\/season-2\.yml.*cells\.c1\.anchors.*gate corner/])
    ctx.writeFile(GALLERY_FILE(), file)
    await ctx.command(admin, '/season reload', /^Reloaded /)
  }
}
