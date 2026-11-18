// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Hall of Fame builders for scenarios: a gallery of cells tagged by command (the wand comes in
// M5), and a hand-built Season 1 cell as TheRanch made them, for /hof import.
import { createHash } from 'node:crypto'
import { assert } from './context.mjs'

/** The ground the flat scenario world stands on: players stand at y = -60. */
export const FLOOR = -60

/**
 * Tags a gallery of `cells` cells with /hof commands. Cell i spans 5 blocks along x from
 * `x + 6 * i`, and the gallery leaves room for one more; each has a figure, an entry, a plaque and
 * a chest as its archive anchor.
 * Resolves with the cells, each { id, key, x0, x1, z0, z1, figure, entry, plaque, archive }.
 */
export async function buildGallery (ctx, admin, { id = 'season-2', season = 2, cells = 4, x = 20, z = 20 } = {}) {
  const width = 6 * (cells + 1)
  await forceload(ctx, admin, x, z, x + width, z + 10)
  await ctx.command(admin, `/hof gallery create ${id} ${season}`, new RegExp(`^Created gallery ${id} for Season ${season}\\.$`))
  await ctx.command(admin, `/hof gallery bounds ${id} ${x} ${FLOOR - 1} ${z} ${x + width} ${FLOOR + 10} ${z + 10}`, new RegExp(`^Gallery ${id} now spans `))
  await ctx.command(admin, `/hof gallery entry ${id} ${x + 1} ${FLOOR} ${z + 1}`, new RegExp(`^Gallery ${id}'s entry is now at `))
  const built = []
  for (let i = 0; i < cells; i++) {
    const x0 = x + 6 * i
    const cell = {
      id: `c${i + 1}`,
      key: `${id}:c${i + 1}`,
      x0,
      x1: x0 + 4,
      z0: z + 2,
      z1: z + 10,
      figure: { x: x0 + 2, y: FLOOR, z: z + 8 },
      entry: { x: x0 + 2, y: FLOOR, z: z + 3 },
      plaque: { x: x0 + 1, y: FLOOR, z: z + 7 },
      archive: { x: x0 + 3, y: FLOOR, z: z + 8 }
    }
    await ctx.command(admin, `/hof cell new ${id} ${cell.id}`, new RegExp(`^Created cell ${cell.id} in gallery ${id}\\.$`))
    await ctx.command(admin, `/hof cell bounds ${cell.key} ${cell.x0} ${FLOOR - 1} ${cell.z0} ${cell.x1} ${FLOOR + 6} ${cell.z1}`, new RegExp(`^Cell ${cell.key} now spans `))
    await anchor(ctx, admin, cell, 'figure', cell.figure, 180)
    await anchor(ctx, admin, cell, 'entry', cell.entry, 0)
    await anchor(ctx, admin, cell, 'plaque', cell.plaque, 180)
    await ctx.command(admin, `/setblock ${cell.archive.x} ${cell.archive.y} ${cell.archive.z} minecraft:chest[facing=north]`, /^Changed the block/)
    await anchor(ctx, admin, cell, 'archive', cell.archive)
    built.push(cell)
  }
  return built
}

/** Keeps the chunks under a build loaded, so commands can reach them however far from spawn. */
async function forceload (ctx, admin, x1, z1, x2, z2) {
  await ctx.command(admin, `/forceload add ${x1} ${z1} ${x2} ${z2}`, /^(Marked|No chunks were marked)/)
}

async function anchor (ctx, admin, cell, type, at, yaw) {
  const facing = yaw === undefined ? '' : ` ${yaw}`
  await ctx.command(admin, `/hof cell anchor ${cell.key} ${type} ${at.x} ${at.y} ${at.z}${facing}`, new RegExp(`^Added an? ${type} anchor to ${cell.key} at `))
}

/** Who owns each cell of a gallery for its season, from /hof gallery info: { c1: 'Alex', c2: null }. */
export async function owners (ctx, admin, gallery, cells) {
  const lines = await ctx.command(admin, `/hof gallery info ${gallery}`, cells.map((c) => new RegExp(`^Cell ${c.id}: `)))
  const result = {}
  for (const line of lines) {
    const [, id, owner] = /^Cell (\S+): (\S+)/.exec(line)
    result[id] = owner === 'free' ? null : owner
  }
  return result
}

/** The cell a player owns, from owners(); throws if they own none. */
export async function cellOf (ctx, admin, gallery, cells, name) {
  const all = await owners(ctx, admin, gallery, cells)
  const id = Object.keys(all).find((c) => all[c] === name)
  assert(id, `${name} owns a cell in ${gallery}: ${JSON.stringify(all)}`)
  return cells.find((c) => c.id === id)
}

/** Whether a position is inside a cell's floor area. */
export function inside (cell, at) {
  return at.x >= cell.x0 && at.x < cell.x1 + 1 && at.z >= cell.z0 && at.z < cell.z1 + 1
}

/** Sends a player to a cell's entry, the way an admin would, and waits for them to arrive. */
export async function visit (ctx, admin, name, cell) {
  await ctx.command(admin, `/tp ${name} ${cell.entry.x + 0.5} ${cell.entry.y} ${cell.entry.z + 0.5}`, /^Teleported /)
  await ctx.eventually(async () => assert(inside(cell, await ctx.position(admin, name)), `${name} is in ${cell.key}`))
}

/** A player's game mode as /data reports it: 0 survival, 2 adventure. */
export async function gameMode (ctx, admin, name) {
  return Number(await ctx.entityData(admin, name, 'playerGameType'))
}

/**
 * Places a block as a player, as BlockPlaceEvent would (bots can't place through the protocol
 * translation, docs/adr/0001). Resolves with whether it was placed.
 */
export async function place (ctx, admin, name, at, block = 'minecraft:stone') {
  const line = await ctx.command(admin, `/scenario place ${name} ${at.x} ${at.y} ${at.z} ${block}`, /^(Placed|Placing) /)
  return line.startsWith('Placed')
}

/** Breaks a block as a player, firing BlockBreakEvent. Resolves with whether it was broken. */
export async function breakBlock (ctx, admin, name, at) {
  const line = await ctx.command(admin, `/scenario break ${name} ${at.x} ${at.y} ${at.z}`, /^(Broke|Breaking) /)
  return line.startsWith('Broke')
}

/**
 * Right-clicks a block as a player, firing PlayerInteractEvent. Through ViaBackwards the server
 * drops a bot's own click on a block, as it does its block placing (docs/adr/0001); clicks on
 * entities get through, so figures are clicked by the bot itself.
 */
export async function use (ctx, admin, name, at) {
  await ctx.command(admin, `/scenario use ${name} ${at.x} ${at.y} ${at.z}`, /^Used /)
}

/** The entity selector for the one entity of a type standing at a block. */
export function at (type, pos) {
  return `@e[type=${type},x=${pos.x},y=${pos.y},z=${pos.z},dx=0,dy=1,dz=0,limit=1]`
}

/** The UUID an offline-mode server gives a name, as Java's UUID.nameUUIDFromBytes makes it. */
export function offlineUuid (name) {
  const md5 = createHash('md5').update(`OfflinePlayer:${name}`, 'utf8').digest()
  md5[6] = (md5[6] & 0x0f) | 0x30
  md5[8] = (md5[8] & 0x3f) | 0x80
  return md5
}

/** A UUID as the int array NBT uses, such as [I;1,2,3,4]. */
export function uuidIntArray (bytes) {
  const ints = []
  for (let i = 0; i < 16; i += 4) ints.push(bytes.readInt32BE(i))
  return `[I;${ints.join(',')}]`
}

/**
 * A textures property value naming a fake skin, so each head's skin can be told apart. Short,
 * because chat commands stop at 256 characters.
 */
export function skinValue (name) {
  return Buffer.from(JSON.stringify({ n: name })).toString('base64')
}

/** A player head item with the profile and skin of `name`, as /item takes it. */
export function headItem (name) {
  return `player_head[profile={id:${uuidIntArray(offlineUuid(name))},name:"${name}",properties:[{name:"textures",value:"${skinValue(name)}"}]}]`
}

/**
 * Builds a Season 1 cell by hand at x..x+10, z..z+8: an armour stand with gear and (unless
 * `head` is null) a player head, `chests` single chests of items along the back wall, and an item
 * frame with an emerald on a stone block. Items go in one /item at a time: chat commands stop at
 * 256 characters. Resolves with the positions.
 */
export async function buildSeasonOneCell (ctx, admin, { x, z, head, chests = 3 }) {
  await forceload(ctx, admin, x, z, x + 16, z + 10)
  const stand = { x: x + 5, y: FLOOR, z: z + 4 }
  const chestAt = []
  for (let i = 0; i < chests; i++) {
    const at = { x: x + 2 + 3 * i, y: FLOOR, z: z + 7 }
    chestAt.push(at)
    const block = `block ${at.x} ${at.y} ${at.z}`
    await ctx.command(admin, `/setblock ${at.x} ${at.y} ${at.z} minecraft:chest[facing=north]`, /^Changed the block/)
    await ctx.command(admin, `/item replace ${block} container.0 with minecraft:diamond ${i + 1}`, /^Replaced /)
    await ctx.command(admin, `/item replace ${block} container.${4 + i} with minecraft:oak_log ${10 + i}`, /^Replaced /)
    await ctx.command(admin, `/item replace ${block} container.26 with minecraft:shulker_box[container=[{slot:0,item:{id:"minecraft:gold_ingot",count:${i + 2}}}]]`, /^Replaced /)
  }
  const wall = { x: x + 1, y: FLOOR + 1, z: z + 8 }
  const frame = { x: x + 1, y: FLOOR + 1, z: z + 7 }
  await ctx.command(admin, `/setblock ${wall.x} ${wall.y} ${wall.z} minecraft:stone`, /^Changed the block/)
  await ctx.command(admin, `/summon minecraft:item_frame ${frame.x} ${frame.y} ${frame.z} {Facing:2b,Item:{id:"minecraft:emerald",count:1}}`, /^Summoned /)
  const tag = `s1x${x}`
  await ctx.command(admin, `/summon minecraft:armor_stand ${stand.x + 0.5} ${stand.y} ${stand.z + 0.5} {ShowArms:1b,Rotation:[180f,0f],Tags:["${tag}"]}`, /^Summoned /)
  const target = `entity @e[type=armor_stand,tag=${tag}]`
  const gear = [
    ['armor.chest', 'minecraft:iron_chestplate'],
    ['armor.feet', 'minecraft:golden_boots'],
    ['weapon.mainhand', 'minecraft:diamond_sword'],
    ['weapon.offhand', 'minecraft:torch 5']
  ]
  if (head) gear.push(['armor.head', headItem(head)])
  for (const [slot, item] of gear) {
    await ctx.command(admin, `/item replace ${target} ${slot} with ${item}`, /^Replaced /)
  }
  return { stand, chests: chestAt, frame }
}

/** The /data text of a block's items, or null for an empty container. */
export async function blockItems (ctx, admin, pos) {
  const line = await ctx.command(admin, `/data get block ${pos.x} ${pos.y} ${pos.z} Items`, /(has the following block data: |^Found no elements matching )/)
  const marker = 'has the following block data: '
  const i = line.indexOf(marker)
  const text = i < 0 ? null : line.slice(i + marker.length)
  return text === '[]' ? null : text
}

/** The /data text at `path` of the one entity a selector finds, or null if none or no such data. */
export async function selectorData (ctx, admin, selector, path) {
  const line = await ctx.command(admin, `/data get entity ${selector} ${path}`, /(has the following entity data: |^Found no elements matching |^No entity was found$)/)
  const marker = 'has the following entity data: '
  const i = line.indexOf(marker)
  return i < 0 ? null : line.slice(i + marker.length)
}
