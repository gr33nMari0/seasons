// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Awards in scenarios: statistics set as the game would have counted them, and trophy anchors in
// the cells buildGallery makes.
import { FLOOR } from './hall.mjs'

/**
 * Sets a vanilla statistic of an online player, such as minecraft:deaths, as if they had earned it
 * (bots can't die, walk or jump their way to a number in time; docs/adr/0003).
 */
export async function setStat (ctx, admin, name, stat, value) {
  await ctx.command(admin, `/scenario stat ${name} ${stat} ${value}`, new RegExp(`^Set ${stat} of ${name} to ${value}\\.$`))
}

/** Each cell's two trophy anchors, beside its entry: { first, second }. */
export function trophies (cell) {
  return {
    first: { x: cell.x0 + 1, y: FLOOR, z: cell.z0 + 3 },
    second: { x: cell.x0 + 3, y: FLOOR, z: cell.z0 + 3 }
  }
}

/** Adds two trophy anchors to each cell. */
export async function addTrophies (ctx, admin, cells) {
  for (const cell of cells) {
    for (const at of Object.values(trophies(cell))) {
      await ctx.command(admin, `/hof cell anchor ${cell.key} trophy ${at.x} ${at.y} ${at.z}`, new RegExp(`^Added a trophy anchor to ${cell.key} at `))
    }
  }
}
