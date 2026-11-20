// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The museum in scenarios: a museum gallery written by hand, as the runbook's egg wall is, with an
// egg plinth for Seasons 2 and 3 and one plinth for anything else; and reading what its plinths
// show.
import { FLOOR, selectorData } from './hall.mjs'
import { forceload } from './zones.mjs'

export const WORLD = 'world/dimensions/minecraft/overworld/seasons'

/** Each plinth: its anchor id for /museum and where its trophy stands. */
export const PLINTHS = {
  eggS2: { anchor: 'museum:egg-s2', trophy: { x: 203, y: FLOOR + 1, z: 305 } },
  eggS3: { anchor: 'museum:egg-s3', trophy: { x: 207, y: FLOOR + 1, z: 305 } },
  core: { anchor: 'museum:core', trophy: { x: 211, y: FLOOR + 1, z: 305 }, plaque: { x: 211, y: FLOOR, z: 303 } }
}

const box = (x) => `{ min: [${x - 1}, ${FLOOR - 1}, 304], max: [${x + 1}, ${FLOOR + 5}, 306] }`
const pos = (at) => `[${at.x}, ${at.y}, ${at.z}]`

/** Writes the museum gallery, reloads and keeps its chunks loaded. */
export async function writeMuseum (ctx, admin) {
  const { eggS2, eggS3, core } = PLINTHS
  ctx.writeFile(`${WORLD}/galleries/museum.yml`, [
    'schema-version: 1',
    'id: museum',
    'kind: museum',
    'dimension: minecraft:overworld',
    `bounds: { min: [200, ${FLOOR - 1}, 300], max: [214, ${FLOOR + 10}, 310] }`,
    `entry: { pos: [201, ${FLOOR}, 301], yaw: 0 }`,
    'cells:',
    '  egg-s2:',
    `    bounds: ${box(eggS2.trophy.x)}`,
    '    anchors:',
    `      - { type: trophy, pos: ${pos(eggS2.trophy)}, egg: 2 }`,
    '  egg-s3:',
    `    bounds: ${box(eggS3.trophy.x)}`,
    '    anchors:',
    `      - { type: trophy, pos: ${pos(eggS3.trophy)}, egg: 3 }`,
    '  core:',
    `    bounds: ${box(core.trophy.x)}`,
    '    anchors:',
    `      - { type: trophy, pos: ${pos(core.trophy)} }`,
    `      - { type: plaque, pos: ${pos(core.plaque)}, yaw: 0 }`,
    ''
  ].join('\n'))
  await ctx.command(admin, '/season reload', /^Reloaded /)
  await forceload(ctx, admin, { x: 207, y: FLOOR, z: 305 }, 16)
}

/** A selector for the one display of a type nearest a block, within reach of it. */
function near (type, at, reach) {
  return `@e[type=minecraft:${type},x=${at.x + 0.5},y=${at.y + 0.5},z=${at.z + 0.5},distance=..${reach},sort=nearest,limit=1]`
}

/** The item id an item display shows at a plinth's trophy, such as "minecraft:dragon_egg", or null. */
export async function shownItem (ctx, admin, plinth) {
  const id = await selectorData(ctx, admin, near('item_display', plinth.trophy, 1), 'item.id')
  return id === null ? null : id.replace(/"/g, '')
}

/** The /data text of the plaque's text near a plinth, or null if there is none. */
export async function plaqueText (ctx, admin, plinth) {
  return selectorData(ctx, admin, near('text_display', plinth.plaque ?? plinth.trophy, 2.5), 'text')
}

/** The /data text of the item an item display shows at a plinth, components and all. */
export async function shownItemData (ctx, admin, plinth) {
  return selectorData(ctx, admin, near('item_display', plinth.trophy, 1), 'item')
}
