// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Stamping in scenarios (M5): where the built-in alcove's blocks and markers go when the game
// places a copy, turned or not, and checking a stamped copy in the world and the gallery file.
import { assert } from './context.mjs'
import { blockIs, cellInFile, structureBlocksIn } from './wand.mjs'

/** The built-in alcove's markers, relative to its minimum corner (its size is 5 × 5 × 5). */
export const ALCOVE = {
  figure: [2, 1, 1],
  archive: [1, 1, 1], // the barrel under the archive marker at [1, 2, 1]
  plaque: [3, 1, 1],
  trophy: [2, 3, 1],
  entry: [2, 1, 3]
}
const WALL = [0, 1, 0]
const MARKERS = [[2, 1, 1], [1, 2, 1], [3, 1, 1], [2, 3, 1], [2, 1, 3]]

/** A relative position turned as the game turns a structure about its origin, then placed. */
export function place (origin, [x, y, z], rotate = 0) {
  const turned = {
    0: [x, z],
    90: [-z, x],
    180: [-x, -z],
    270: [z, -x]
  }[rotate]
  return { x: origin.x + turned[0], y: origin.y + y, z: origin.z + turned[1] }
}

/** The box a turned copy covers. */
export function footprint (origin, rotate = 0) {
  const a = place(origin, [0, 0, 0], rotate)
  const b = place(origin, [4, 4, 4], rotate)
  return {
    min: { x: Math.min(a.x, b.x), y: a.y, z: Math.min(a.z, b.z) },
    max: { x: Math.max(a.x, b.x), y: b.y, z: Math.max(a.z, b.z) }
  }
}

/** Checks one stamped copy, in the world and in the gallery file. */
export async function checkCopy (ctx, admin, cell, origin, rotate = 0) {
  const box = footprint(origin, rotate)
  const found = cellInFile(ctx, cell)
  const bounds = { min: [box.min.x, box.min.y, box.min.z], max: [box.max.x, box.max.y, box.max.z] }
  assert(JSON.stringify(found.bounds) === JSON.stringify(bounds), `${cell} spans its copy: ${JSON.stringify(found.bounds)}, not ${JSON.stringify(bounds)}`)
  const yaw = { 0: 0, 90: 90, 180: 180, 270: -90 }[rotate]
  for (const [type, rel] of Object.entries(ALCOVE)) {
    const want = place(origin, rel, rotate)
    const anchor = found.anchors.find((a) => a.type === type)
    assert(anchor && anchor.x === want.x && anchor.y === want.y && anchor.z === want.z, `${cell}'s ${type} anchor is at ${JSON.stringify(want)}: ${JSON.stringify(anchor)}`)
    assert(anchor.yaw === yaw, `${cell}'s ${type} anchor faces ${yaw}: ${anchor.yaw}`)
  }
  // Where the game put the blocks: the barrel under the archive anchor's name, the wall, and air
  // where every marker stood.
  assert(await blockIs(ctx, admin, place(origin, ALCOVE.archive, rotate), 'minecraft:barrel'), `${cell}: the barrel is where the archive anchor is`)
  assert(await blockIs(ctx, admin, place(origin, WALL, rotate), 'minecraft:stone_bricks'), `${cell}: the wall is where the template has it`)
  for (const rel of MARKERS) {
    assert(await blockIs(ctx, admin, place(origin, rel, rotate), 'minecraft:air'), `${cell}: the marker at ${rel} is air`)
  }
  assert(!await structureBlocksIn(ctx, admin, box.min, box.max), `${cell}: no marker is left`)
}


/**
 * A two-session scenario: the server stops dead at one step of a two-copy /hof stamp (Runtime.halt,
 * through the scenario hooks in docs/adr/0003, armed by the admin who stamps) and starts again.
 * Recovery finishes the stamp at startup: each copy's blocks once, no marker left, both cells in
 * the gallery file, and nothing waiting in the journal.
 */
export function stampCrashScenario (name, point) {
  const origin = { x: 22, y: -60, z: 22 }
  return {
    name,
    sessions: [
      async (ctx) => {
        const admin = await ctx.admin()
        await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
        await ctx.command(admin, '/forceload add 15 15 60 40', /^(Marked|No chunks were marked)/)
        await ctx.command(admin, '/hof gallery create season-2 2', /^Created gallery season-2 /)
        await ctx.command(admin, '/hof gallery bounds season-2 20 -61 20 50 -40 35', /^Gallery season-2 now spans /)
        await ctx.command(admin, '/tp SeasonsAdmin 22.5 -60 22.5', /^Teleported /)
        await ctx.command(admin, '/save-all flush', /^Saved the game/)
        await ctx.command(admin, `/scenario crash-at ${point} SeasonsAdmin`, /^Armed crash point /)
        const gone = ctx.disconnected(admin)
        admin.chat('/hof stamp alcove season-2 2')
        await gone
        ctx.log(`the server halted at ${point}`)
      },
      async (ctx) => {
        const admin = await ctx.admin()
        await ctx.command(admin, '/forceload add 15 15 60 40', /^(Marked|No chunks were marked)/)
        await ctx.eventually(async () => {
          await ctx.command(admin, '/season status', /^Journal: .*, 0 waiting for recovery$/)
        }, 20_000)
        await checkCopy(ctx, admin, 'c1', origin)
        await checkCopy(ctx, admin, 'c2', { ...origin, x: origin.x + 5 })
        assert(await blockIs(ctx, admin, { x: 32, y: -60, z: 22 }, 'minecraft:air'), 'no third copy')
        await ctx.command(admin, '/season validate', /^All config files are valid\.$/)
      }
    ]
  }
}
