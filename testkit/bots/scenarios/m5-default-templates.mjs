// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The two templates in the jar (M5): with no templates/ folder, alcove and plinth stamp from the
// jar; a file of the same name in templates/ overrides one. The plinth is a figure on a raised block
// with a plaque in front and a trophy beside it, so it serves a season gallery's row of plinths and
// a museum, where its trophy anchor takes /museum add.
import { copyFileSync, mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { PLINTHS, shownItem, writeMuseum } from '../lib/museum.mjs'
import { standAt } from '../lib/visits.mjs'
import { WORLD, blockIs, cellInFile, structureBlocksIn } from '../lib/wand.mjs'
import { forceload } from '../lib/zones.mjs'

const REPO = join(dirname(fileURLToPath(import.meta.url)), '../../..')

export default {
  name: 'm5-default-templates',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await forceload(ctx, admin, { x: 40, z: 30 }, 30)
    await ctx.command(admin, '/hof gallery create season-2 2', /^Created gallery season-2 /)
    await ctx.command(admin, `/hof gallery bounds season-2 20 ${FLOOR - 1} 20 60 -40 40`, /^Gallery season-2 now spans /)

    // Both from the jar.
    await standAt(ctx, admin, 'SeasonsAdmin', { x: 22, y: FLOOR, z: 22 })
    await ctx.command(admin, '/hof stamp alcove season-2 1', /^Stamped 1 copy of alcove \(built in\) into season-2: c1\.$/)
    await standAt(ctx, admin, 'SeasonsAdmin', { x: 30, y: FLOOR, z: 22 })
    await ctx.command(admin, '/hof stamp plinth season-2 1', /^Stamped 1 copy of plinth \(built in\) into season-2: c2\.$/)
    const plinth = cellInFile(ctx, 'c2')
    assert(JSON.stringify(plinth.bounds) === JSON.stringify({ min: [30, FLOOR, 22], max: [32, FLOOR + 3, 24] }), `the plinth is 3 × 4 × 3: ${JSON.stringify(plinth.bounds)}`)
    const byType = Object.fromEntries(plinth.anchors.map((a) => [a.type, [a.x, a.y, a.z]]))
    // The figure stands on the plinth block, the plaque in front of it, the trophy beside it.
    const want = { figure: [31, FLOOR + 2, 23], plaque: [31, FLOOR + 1, 24], trophy: [30, FLOOR + 1, 23] }
    assert(Object.keys(want).every((type) => JSON.stringify(byType[type]) === JSON.stringify(want[type])) && Object.keys(byType).length === 3, `the plinth's anchors: ${JSON.stringify(byType)}`)
    assert(await blockIs(ctx, admin, { x: 31, y: FLOOR + 1, z: 23 }, 'minecraft:chiseled_stone_bricks'), 'the plinth block is there')
    assert(!await structureBlocksIn(ctx, admin, { x: 22, y: FLOOR, z: 22 }, { x: 32, y: FLOOR + 4, z: 26 }), 'no marker is left')
    await ctx.command(admin, '/season validate', /^All config files are valid\.$/)

    // A file in templates/ overrides the jar's.
    const file = join(ctx.serverDir, WORLD, 'templates/alcove.nbt')
    mkdirSync(dirname(file), { recursive: true })
    copyFileSync(join(REPO, 'templates/plinth.nbt'), file)
    await standAt(ctx, admin, 'SeasonsAdmin', { x: 40, y: FLOOR, z: 22 })
    await ctx.command(admin, '/hof stamp alcove season-2 1', /^Stamped 1 copy of alcove \(from templates\/\) into season-2: c3\.$/)
    assert(JSON.stringify(cellInFile(ctx, 'c3').bounds) === JSON.stringify({ min: [40, FLOOR, 22], max: [42, FLOOR + 3, 24] }), 'the file was used')

    // A plinth in the museum takes a trophy.
    await writeMuseum(ctx, admin)
    await standAt(ctx, admin, 'SeasonsAdmin', { x: 205, y: FLOOR, z: 307 })
    await ctx.command(admin, '/hof stamp plinth museum 1', /^Stamped 1 copy of plinth \(built in\) into museum: c1\.$/)
    await ctx.command(admin, '/clear SeasonsAdmin', /./)
    await ctx.command(admin, '/give SeasonsAdmin minecraft:heavy_core', /^Gave 1 /)
    await ctx.command(admin, '/museum add museum:c1 Heavy core', /^Put minecraft:heavy_core in museum:c1\.$/)
    const trophy = { trophy: { x: 205, y: FLOOR + 1, z: 308 } }
    await ctx.eventually(async () => assert(await shownItem(ctx, admin, trophy) === 'minecraft:heavy_core', 'the stamped plinth shows the heavy core'))
    assert(await shownItem(ctx, admin, PLINTHS.core) === null, 'the other plinths are untouched')
  }
}
