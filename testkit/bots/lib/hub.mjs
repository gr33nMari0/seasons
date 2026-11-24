// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The Hall's hub in scenarios (questions 243 to 259): the box beside buildGallery's gallery, its
// Hall spawn, links, gates and the Hall check.
import { assert } from './context.mjs'
import { FLOOR } from './hall.mjs'
import { WORLD, readYaml } from './wand.mjs'

/** The hub hallToVisit marks: beside the gallery's west side (x 20), as tall as the gallery. */
export const HUB = { x1: 8, y1: FLOOR - 1, z1: 20, x2: 19, y2: FLOOR + 10, z2: 30 }

/** The Hall spawn hallToVisit sets, in the hub one block from the gallery. */
export const HALL_SPAWN = { x: 18, y: FLOOR, z: 21 }

export const HALL_FILE = `${WORLD}/hall.yml`

/** The box's corners as /hof hall hub takes them. */
export function corners (box) {
  return `${box.x1} ${box.y1} ${box.z1} ${box.x2} ${box.y2} ${box.z2}`
}

/** Marks a hub by command, keeps its chunks loaded and sets the Hall spawn in it. */
export async function markHub (ctx, admin, { hub = HUB, spawn = HALL_SPAWN, yaw = 0 } = {}) {
  await ctx.command(admin, `/forceload add ${hub.x1} ${hub.z1} ${hub.x2} ${hub.z2}`, /^(Marked|No chunks were marked)/)
  await ctx.command(admin, `/hof hall hub ${corners(hub)}`, new RegExp(`^The Hall's hub now spans ${hub.x1}, ${hub.y1}, ${hub.z1} to ${hub.x2}, ${hub.y2}, ${hub.z2}\\.$`))
  await ctx.command(admin, `/hof hall spawn ${spawn.x} ${spawn.y} ${spawn.z} ${yaw}`, new RegExp(`^The Hall spawn is now at ${spawn.x}, ${spawn.y}, ${spawn.z}\\.$`))
}

/** What hall.yml says, as plain objects. */
export function hallFile (ctx) {
  return readYaml(ctx.readFile(HALL_FILE))
}

export const LINKS_FILE = `${WORLD}/links.yml`

/** What links.yml says, as plain objects (M8: the links and named points). */
export function linksFile (ctx) {
  return readYaml(ctx.readFile(LINKS_FILE))
}

/** Whether a position is inside a box, as Seasons counts blocks. */
export function inBox (box, at) {
  const x = Math.floor(at.x)
  const y = Math.floor(at.y)
  const z = Math.floor(at.z)
  return x >= box.x1 && x <= box.x2 && y >= box.y1 && y <= box.y2 && z >= box.z1 && z <= box.z2
}

/**
 * Makes the block at `at` a link with /hof link add, leading to `to` ('hall', 'season 3', or ''
 * for nowhere yet). Resolves with the reply.
 */
export async function addLink (ctx, admin, id, at, to, expect) {
  const words = [to, at.x, at.y, at.z].filter((w) => w !== '' && w !== undefined).join(' ')
  return ctx.command(admin, `/hof link add ${id} ${words}`, expect ?? new RegExp(`^Link ${id} at ${at.x}, ${at.y}, ${at.z} leads `))
}

/** Resolves once every block of a box is `block`. */
export async function waitBlocks (ctx, admin, box, block) {
  await ctx.eventually(async () => {
    for (let x = box.x1; x <= box.x2; x++) {
      for (let y = box.y1; y <= box.y2; y++) {
        for (let z = box.z1; z <= box.z2; z++) {
          assert(await ctx.holds(admin, `block ${x} ${y} ${z} ${block}`), `${x}, ${y}, ${z} is ${block}`)
        }
      }
    }
  })
}
