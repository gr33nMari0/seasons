// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A72. A gallery entry and the Hall spawn set as boxes spread arriving players over their free
// floor; an exit set as a doorway plane and one as a hole in a ceiling each send whoever passes
// through; a link area in a sky frame takes an elytra flight through it to the Hall (questions 279).
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { HOME, bed, hallToVisit, hof, middle, move, standAt, waitNear } from '../lib/visits.mjs'

const NAMES = ['Ann', 'Ben', 'Cat']

function inBox (at, box) {
  return Math.floor(at.x) >= box.x1 && Math.floor(at.x) <= box.x2 && Math.floor(at.z) >= box.z1 && Math.floor(at.z) <= box.z2
}

export default {
  name: 'a72-areas',
  async run (ctx) {
    const admin = await ctx.admin()
    const bots = []
    for (const name of NAMES) bots.push(await ctx.join(name))
    await hallToVisit(ctx, admin, { sealed: null })
    await bed(ctx, admin, { x: HOME.x + 1, y: FLOOR, z: HOME.z })

    // The Hall spawn as a box: three arrivals spread over its floor.
    const spawn = { x1: 15, x2: 18, z1: 21, z2: 24 }
    await ctx.command(admin, `/hof hall spawn ${spawn.x1} ${FLOOR} ${spawn.z1} ${spawn.x2} ${FLOOR} ${spawn.z2} 0`, /^The Hall spawn is now 15, -60, 21 to 18, -60, 24\.$/)
    const landed = []
    for (const [i, name] of NAMES.entries()) {
      await standAt(ctx, admin, name, HOME)
      await hof(ctx, bots[i], '/hof', /^Welcome to the Hall of Fame\./)
      landed.push(await ctx.eventually(async () => {
        const at = await ctx.position(admin, name)
        assert(inBox(at, spawn), `${name} landed in the spawn box: ${JSON.stringify(at)}`)
        return at
      }))
    }
    for (let i = 0; i < landed.length; i++) {
      for (let j = i + 1; j < landed.length; j++) {
        assert(Math.hypot(landed[i].x - landed[j].x, landed[i].z - landed[j].z) >= 1, `${NAMES[i]} and ${NAMES[j]} landed apart`)
      }
    }

    // A gallery entry as a box.
    const entry = { x1: 21, x2: 23, z1: 21, z2: 21 }
    await ctx.command(admin, `/hof gallery entry season-2 21 ${FLOOR} 21 23 ${FLOOR} 21 90`, /^Gallery season-2's entry is now 21, -60, 21 to 23, -60, 21\.$/)
    await hof(ctx, bots[0], '/hof 2', /^Welcome to the Hall of Fame\./)
    await ctx.eventually(async () => assert(inBox(await ctx.position(admin, 'Ann'), entry), 'Ann landed in the gallery entry'))

    // A doorway-plane exit at x = 9 in the hub: walking through it goes back.
    await ctx.command(admin, `/hof hall exit 9 ${FLOOR} 24 9 ${FLOOR + 2} 26`, /^Added a Hall exit 9, -60, 24 to 9, -58, 26\.$/)
    await standAt(ctx, admin, 'Ben', { x: 10, y: FLOOR, z: 25 })
    let told = ctx.waitForMessages(bots[1], /^Back where you left\.$/)
    await move(ctx, admin, 'Ben', { x: 8.5, y: FLOOR, z: 25.5 }, 'walk')
    await told
    await waitNear(ctx, admin, 'Ben', middle(HOME))

    // A hole in a ceiling at y -56: flying up through it goes back too.
    await ctx.command(admin, `/hof hall exit 12 ${FLOOR + 4} 27 13 ${FLOOR + 4} 28`, /^Added a Hall exit 12, -56, 27 to 13, -56, 28\.$/)
    await standAt(ctx, admin, 'Cat', { x: 12, y: FLOOR + 2, z: 27 })
    told = ctx.waitForMessages(bots[2], /^Back where you left\.$/)
    await move(ctx, admin, 'Cat', { x: 12.5, y: FLOOR + 6, z: 27.5 }, 'fly')
    await told

    // A frame in the sky, as a link area: a fast flight through it lands in the Hall.
    await ctx.command(admin, '/forceload add 140 90 160 110', /^(Marked|No chunks were marked)/)
    await ctx.command(admin, `/hof link add sky hall area 150 ${FLOOR + 20} 100 150 ${FLOOR + 24} 104`, /^Link sky, an area from 150, -40, 100 to 150, -36, 104, leads to the Hall spawn\.$/)
    await standAt(ctx, admin, 'Ben', { x: 147, y: FLOOR + 22, z: 102 })
    told = ctx.waitForMessages(bots[1], /^Welcome to the Hall of Fame\./)
    await move(ctx, admin, 'Ben', { x: 153.5, y: FLOOR + 22, z: 102.5 }, 'fly')
    await told
    await ctx.eventually(async () => assert(inBox(await ctx.position(admin, 'Ben'), spawn), 'Ben is at the Hall spawn'))
  }
}
