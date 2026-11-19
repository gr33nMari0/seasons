// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A gallery with access mode gate (questions 169): cell c1 has a gate plane across its front, two
// blocks high and three wide, between its entry anchor and its figure.
import { assert } from './context.mjs'
import { FLOOR, buildGallery } from './hall.mjs'

const FILE = 'world/dimensions/minecraft/overworld/seasons/galleries/season-2.yml'

/** The gate plane's blocks for buildGallery's cell c1 (x 20 to 24, z 22 to 30). */
export const GATE = { x1: 21, x2: 23, y1: FLOOR, y2: FLOOR + 1, z: 24 }

/** Records Season 2, builds its gallery with a gate on c1, and makes its access gate. */
export async function gateGallery (ctx, admin) {
  await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
  const cells = await buildGallery(ctx, admin, { cells: 2 })
  await ctx.command(admin, `/hof cell anchor season-2:c1 gate ${GATE.x1} ${GATE.y1} ${GATE.z}`, /^Added a gate anchor to season-2:c1 at /)
  await ctx.command(admin, `/hof cell anchor season-2:c1 gate ${GATE.x2} ${GATE.y2} ${GATE.z}`, /^Added a gate anchor to season-2:c1 at /)
  ctx.writeFile(FILE, ctx.readFile(FILE).replace(/^cells:/m, 'access: gate\ncells:'))
  await ctx.command(admin, '/season reload', /^Reloaded /)
  return cells
}

/** Whether every block of the gate plane is `block`. */
export async function gateIs (ctx, admin, block) {
  for (let x = GATE.x1; x <= GATE.x2; x++) {
    for (let y = GATE.y1; y <= GATE.y2; y++) {
      if (!await ctx.holds(admin, `block ${x} ${y} ${GATE.z} ${block}`)) return false
    }
  }
  return true
}

/** Resolves once the gate plane is all `block`. */
export async function waitGate (ctx, admin, block) {
  await ctx.eventually(async () => assert(await gateIs(ctx, admin, block), `the gate is ${block}`))
}
