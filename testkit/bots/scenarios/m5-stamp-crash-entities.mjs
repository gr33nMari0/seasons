// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// A template with an entity in it (an empty item frame on the floor), stamped twice, and the server
// stops dead once both copies are placed and saved, before the gallery file is written. Startup
// places the copies again; each copy's frame is the one the stamp marked, taken away first, so each
// copy still has exactly one (questions 234). (A creature such as an armour stand wouldn't do: the
// Hall lets no creature spawn but Seasons' own and commands'.)
import { assert } from '../lib/context.mjs'
import { blockIs, cellIds, marker, saveStructure, structureBlocksIn } from '../lib/wand.mjs'

const FLOOR = -60
const BUILD = { x: 200, y: FLOOR, z: 200 }
const ORIGIN = { x: 22, y: FLOOR, z: 22 }

/** The item frames in a 3 × 3 × 3 copy at `origin`, by /execute if entity. */
async function frames (ctx, admin, origin) {
  const line = await ctx.command(admin, `/execute if entity @e[type=minecraft:item_frame,x=${origin.x},y=${origin.y},z=${origin.z},dx=2,dy=2,dz=2]`, /^Test (passed|failed)/)
  const count = /count: (\d+)/i.exec(line)
  return count ? Number(count[1]) : 0
}

export default {
  name: 'm5-stamp-crash-entities',
  sessions: [
    async (ctx) => {
      const admin = await ctx.admin()
      await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
      await ctx.command(admin, '/forceload add 15 15 60 40', /^(Marked|No chunks were marked)/)
      await ctx.command(admin, '/forceload add 190 190 210 210', /^(Marked|No chunks were marked)/)
      await ctx.command(admin, '/hof gallery create season-2 2', /^Created gallery season-2 /)
      await ctx.command(admin, '/hof gallery bounds season-2 20 -61 20 50 -40 35', /^Gallery season-2 now spans /)
      // The booth: a floor, a figure marker and an empty item frame lying on the floor, saved with
      // its entities.
      await ctx.command(admin, `/fill ${BUILD.x} ${BUILD.y} ${BUILD.z} ${BUILD.x + 2} ${BUILD.y} ${BUILD.z + 2} minecraft:smooth_stone`, /^Successfully filled/)
      await marker(ctx, admin, { x: BUILD.x + 1, y: BUILD.y + 1, z: BUILD.z + 1 }, 'figure')
      await ctx.command(admin, `/summon minecraft:item_frame ${BUILD.x} ${BUILD.y + 1} ${BUILD.z + 2} {Facing:1b}`, /^Summoned /)
      await saveStructure(ctx, admin, 'framebooth', BUILD, { x: 3, y: 3, z: 3 }, { entities: true })
      await ctx.command(admin, `/tp SeasonsAdmin ${ORIGIN.x + 0.5} ${ORIGIN.y} ${ORIGIN.z + 0.5}`, /^Teleported /)
      await ctx.command(admin, '/save-all flush', /^Saved the game/)
      await ctx.command(admin, '/scenario crash-at STAMP_PLACED SeasonsAdmin', /^Armed crash point /)
      const gone = ctx.disconnected(admin)
      admin.chat('/hof stamp framebooth season-2 2')
      await gone
      ctx.log('the server halted at STAMP_PLACED')
    },
    async (ctx) => {
      const admin = await ctx.admin()
      await ctx.command(admin, '/forceload add 15 15 60 40', /^(Marked|No chunks were marked)/)
      await ctx.eventually(async () => {
        await ctx.command(admin, '/season status', /^Journal: .*, 0 waiting for recovery$/)
      }, 20_000)
      assert(JSON.stringify(cellIds(ctx)) === JSON.stringify(['c1', 'c2']), `both cells are written: ${cellIds(ctx)}`)
      for (const [i, origin] of [ORIGIN, { ...ORIGIN, x: ORIGIN.x + 3 }].entries()) {
        assert(await frames(ctx, admin, origin) === 1, `copy ${i + 1} has one item frame: ${await frames(ctx, admin, origin)}`)
        assert(await blockIs(ctx, admin, { x: origin.x + 1, y: origin.y + 1, z: origin.z + 1 }, 'minecraft:air'), `copy ${i + 1}'s marker is air`)
        assert(!await structureBlocksIn(ctx, admin, origin, { x: origin.x + 2, y: origin.y + 2, z: origin.z + 2 }), `copy ${i + 1} has no marker left`)
      }
      await ctx.command(admin, '/season validate', /^All config files are valid\.$/)
    }
  ]
}
