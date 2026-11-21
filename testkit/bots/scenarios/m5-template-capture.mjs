// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Template capture (M5, questions 230, 231 and 235): a builder makes a cell with structure blocks
// in DATA mode for its markers and saves it with a structure block as seasons:<name>; /hof stamp
// finds it by name. A file of that name in templates/ wins over it. A template is refused, with
// the reason and nothing changed, when it has no figure marker, two plaques, one gate corner, an
// unknown seasons: marker, an archive marker over nothing, a container holding items or a loot
// table, a dropped item, or a block the Hall doesn't allow (a hopper, a spawner). An unknown name lists the templates there are.
import { copyFileSync, mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { standAt } from '../lib/visits.mjs'
import { GALLERY_FILE, WORLD, blockIs, cellInFile, marker, saveStructure, structureBlocksIn } from '../lib/wand.mjs'
import { forceload } from '../lib/zones.mjs'

const REPO = join(dirname(fileURLToPath(import.meta.url)), '../../..')
const SIZE = { x: 3, y: 3, z: 3 }

/**
 * Builds a 3 × 3 × 3 template on a floor of smooth stone at `at` (its minimum corner), with
 * `extras` as [relative x, y, z, what] (a marker type, or a /setblock block), and saves it.
 */
async function template (ctx, admin, name, at, extras) {
  await ctx.command(admin, `/fill ${at.x} ${at.y} ${at.z} ${at.x + 2} ${at.y + 2} ${at.z + 2} minecraft:air`, /^(Successfully filled|No blocks were filled)/)
  await ctx.command(admin, `/fill ${at.x} ${at.y} ${at.z} ${at.x + 2} ${at.y} ${at.z + 2} minecraft:smooth_stone`, /^Successfully filled/)
  let entities = false
  for (const [x, y, z, what] of extras) {
    const pos = { x: at.x + x, y: at.y + y, z: at.z + z }
    if (what.startsWith('summon ')) {
      // An entity, saved with the template: `summon <type> <nbt>`.
      const [, type, nbt] = /^summon (\S+) (.*)$/.exec(what)
      await ctx.command(admin, `/summon ${type} ${pos.x + 0.5} ${pos.y} ${pos.z + 0.5} ${nbt}`, /^Summoned /)
      entities = true
    } else if (what.startsWith('minecraft:')) {
      await ctx.command(admin, `/setblock ${pos.x} ${pos.y} ${pos.z} ${what}`, /^Changed the block/)
    } else {
      await marker(ctx, admin, pos, what)
    }
  }
  await saveStructure(ctx, admin, name, at, SIZE, { entities })
  if (entities) {
    await ctx.command(admin, `/kill @e[type=!player,x=${at.x},y=${at.y},z=${at.z},dx=2,dy=2,dz=2]`, /^Killed /)
  }
}

export default {
  name: 'm5-template-capture',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await forceload(ctx, admin, { x: 60, z: 40 }, 45)
    await forceload(ctx, admin, { x: 230, z: 200 }, 40)
    await ctx.command(admin, '/hof gallery create season-2 2', /^Created gallery season-2 /)
    await ctx.command(admin, `/hof gallery bounds season-2 20 ${FLOOR - 1} 20 100 -40 60`, /^Gallery season-2 now spans /)
    await ctx.command(admin, `/hof gallery entry season-2 21 ${FLOOR} 21`, /^Gallery season-2's entry is now at /)

    // The booth: a figure in the middle, a plaque, a barrel with an archive marker on it, an entry.
    await template(ctx, admin, 'booth', { x: 200, y: FLOOR, z: 200 }, [
      [1, 1, 1, 'figure'],
      [0, 1, 0, 'plaque'],
      [2, 1, 0, 'minecraft:barrel'],
      [2, 2, 0, 'archive'],
      [1, 1, 2, 'entry']
    ])
    await standAt(ctx, admin, 'SeasonsAdmin', { x: 30, y: FLOOR, z: 30 })
    await ctx.command(admin, '/hof stamp booth season-2 1', /^Stamped 1 copy of booth \(from a structure block\) into season-2: c1\.$/)
    const c1 = cellInFile(ctx, 'c1')
    assert(JSON.stringify(c1.bounds) === JSON.stringify({ min: [30, FLOOR, 30], max: [32, FLOOR + 2, 32] }), `c1 spans the booth: ${JSON.stringify(c1.bounds)}`)
    const expected = [
      { type: 'figure', x: 31, y: FLOOR + 1, z: 31, yaw: 0 },
      { type: 'plaque', x: 30, y: FLOOR + 1, z: 30, yaw: 0 },
      // The archive marker anchors the barrel under it (question 231).
      { type: 'archive', x: 32, y: FLOOR + 1, z: 30, yaw: 0 },
      { type: 'entry', x: 31, y: FLOOR + 1, z: 32, yaw: 0 }
    ]
    const sorted = (list) => JSON.stringify([...list].sort((a, b) => a.type.localeCompare(b.type)))
    assert(sorted(c1.anchors) === sorted(expected), `the markers are c1's anchors: ${JSON.stringify(c1.anchors)}`)
    assert(await blockIs(ctx, admin, { x: 32, y: FLOOR + 1, z: 30 }, 'minecraft:barrel'), 'the barrel was placed')
    assert(await blockIs(ctx, admin, { x: 31, y: FLOOR + 1, z: 31 }, 'minecraft:air'), 'the figure marker is air')
    assert(await blockIs(ctx, admin, { x: 32, y: FLOOR + 2, z: 30 }, 'minecraft:air'), 'the archive marker is air')
    assert(!await structureBlocksIn(ctx, admin, { x: 30, y: FLOOR, z: 30 }, { x: 32, y: FLOOR + 2, z: 32 }), 'no marker is left')

    // A file in templates/ wins over a saved structure of the same name: here the alcove's.
    const file = join(ctx.serverDir, WORLD, 'templates/booth.nbt')
    mkdirSync(dirname(file), { recursive: true })
    copyFileSync(join(REPO, 'templates/alcove.nbt'), file)
    await standAt(ctx, admin, 'SeasonsAdmin', { x: 40, y: FLOOR, z: 30 })
    await ctx.command(admin, '/hof stamp booth season-2 1', /^Stamped 1 copy of booth \(from templates\/\) into season-2: c2\.$/)
    assert(JSON.stringify(cellInFile(ctx, 'c2').bounds) === JSON.stringify({ min: [40, FLOOR, 30], max: [44, FLOOR + 4, 34] }), 'c2 is the alcove\'s size')

    // Templates that can't be stamped. Nothing changes for any of them.
    const refusals = [
      ['nofigure', [[1, 1, 1, 'plaque']], 'it has no seasons:figure marker'],
      ['twoplaques', [[1, 1, 1, 'figure'], [0, 1, 0, 'plaque'], [2, 1, 0, 'plaque']], 'it has 2 seasons:plaque markers; a cell has at most 1'],
      ['onegate', [[1, 1, 1, 'figure'], [0, 1, 0, 'gate']], 'it has 1 seasons:gate marker; a gate needs 2, one at each corner'],
      ['podium', [[1, 1, 1, 'figure'], [0, 1, 0, 'seasons:podium']], 'seasons:podium at 0, 1, 0 isn\'t a marker Seasons knows'],
      ['floating', [[1, 1, 1, 'figure'], [0, 2, 0, 'archive']], 'the seasons:archive marker at 0, 2, 0 must sit on the block it anchors'],
      ['stash', [[1, 1, 1, 'figure'], [0, 1, 0, 'minecraft:chest{Items:[{Slot:0b,id:"minecraft:diamond",count:3}]}']], 'the minecraft:chest at 0, 1, 0 holds items; templates hold none'],
      ['hopper', [[1, 1, 1, 'figure'], [0, 1, 0, 'minecraft:hopper']], 'it has a minecraft:hopper at 0, 1, 0, which the Hall doesn\'t allow'],
      // Items from nothing: a loot table fills the chest when it is first opened; a dropped item,
      // and a spawner.
      ['loot', [[1, 1, 1, 'figure'], [0, 1, 0, 'minecraft:chest{LootTable:"minecraft:chests/simple_dungeon"}']], 'the minecraft:chest at 0, 1, 0 holds items; templates hold none'],
      ['dropped', [[1, 1, 1, 'figure'], [0, 1, 2, 'summon minecraft:item {Item:{id:"minecraft:diamond",count:1},NoGravity:1b,PickupDelay:32767s,Age:-32768s}']], 'a minecraft:item in it carries items; templates hold none'],
      ['spawner', [[1, 1, 1, 'figure'], [0, 1, 0, 'minecraft:spawner']], 'it has a minecraft:spawner at 0, 1, 0, which the Hall doesn\'t allow']
    ]
    const before = ctx.readFile(GALLERY_FILE())
    await standAt(ctx, admin, 'SeasonsAdmin', { x: 50, y: FLOOR, z: 30 })
    for (const [i, [name, extras, reason]] of refusals.entries()) {
      await template(ctx, admin, name, { x: 220 + 5 * i, y: FLOOR, z: 200 }, extras)
      await ctx.command(admin, `/hof stamp ${name} season-2 1`, new RegExp(`^Template ${name} can't be stamped: ${reason.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\.$`))
    }
    assert(ctx.readFile(GALLERY_FILE()) === before, 'no refused stamp changed the gallery')
    assert(await blockIs(ctx, admin, { x: 50, y: FLOOR, z: 30 }, 'minecraft:air'), 'no refused stamp placed anything')

    // An unknown name lists what there is.
    await ctx.command(admin, '/hof stamp nothing season-2 1', /^There's no template called nothing\. Templates: .*\balcove\b.*\bbooth\b.*\bplinth\b/)
  }
}
