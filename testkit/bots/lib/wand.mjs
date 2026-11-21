// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The wand and templates in scenarios (M5): clicking blocks with the wand, the anchor menu, saving
// a structure with a real structure block, and reading what a gallery file says about its cells.
import { assert } from './context.mjs'

export const WORLD = 'world/dimensions/minecraft/overworld/seasons'
export const GALLERY_FILE = (id = 'season-2') => `${WORLD}/galleries/${id}.yml`

/** The icon each choice has in the wand's anchor menu. */
export const ICONS = {
  figure: 'armor_stand',
  archive: 'chest',
  plaque: 'lectern',
  trophy: 'item_frame',
  entry: 'oak_door',
  gate: 'iron_bars',
  egg: 'dragon_egg',
  link: 'lodestone',
  remove: 'barrier'
}

/**
 * Clicks a block as a player holding what they hold, firing PlayerInteractEvent (bots' own block
 * clicks don't get through the protocol translation, docs/adr/0001). `left` for a left click,
 * `sneak` while sneaking, `face` the side clicked (default up). Resolves with the test plugin's
 * reply.
 */
export async function click (ctx, admin, name, at, { left = false, sneak = false, face = 'up' } = {}) {
  const words = [left ? 'left' : null, sneak ? 'sneak' : null, `face=${face}`].filter(Boolean).join(' ')
  return ctx.command(admin, `/scenario use ${name} ${at.x} ${at.y} ${at.z} ${words}`, /^Used /)
}

/** Selects two corners with the wand: sneak and left-click one, sneak and right-click the other. */
export async function select (ctx, admin, bot, from, to, expect = /^Selected /) {
  const told = ctx.waitForMessages(bot, expect)
  await click(ctx, admin, bot.username, from, { left: true, sneak: true })
  await click(ctx, admin, bot.username, to, { sneak: true })
  return told
}

/** Gives a bot the wand and checks it arrived in its hand. */
export async function giveWand (ctx, bot) {
  await ctx.command(bot, '/hof wand', /^Gave you the Seasons wand\./)
  await ctx.eventually(async () => assert(bot.heldItem?.name === 'blaze_rod', `${bot.username} holds the wand: ${bot.heldItem?.name}`))
}

/**
 * Right-clicks a block with the wand and resolves with the anchor menu that opens, once its
 * choices are shown.
 */
export async function anchorMenu (ctx, admin, bot, at, face = 'up') {
  const opened = new Promise((resolve) => bot.once('windowOpen', resolve))
  await click(ctx, admin, bot.username, at, { face })
  const menu = await opened
  await ctx.eventually(async () => assert(choices(bot.currentWindow).length > 0, 'the anchor menu shows its choices'))
  return menu
}

/** The choices an anchor menu offers, by name. */
export function choices (menu) {
  const names = Object.fromEntries(Object.entries(ICONS).map(([choice, icon]) => [icon, choice]))
  return menu.slots.slice(0, 9).filter(Boolean).map((item) => names[item.name] ?? item.name)
}

/** Picks a choice in the open anchor menu and resolves with the reply. */
export async function pickAnchor (ctx, bot, choice, expect) {
  const menu = bot.currentWindow
  const slot = menu.slots.slice(0, 9).findIndex((item) => item?.name === ICONS[choice])
  assert(slot >= 0, `the anchor menu offers ${choice}: ${choices(menu)}`)
  const reply = ctx.waitForMessages(bot, expect)
  await bot.clickWindow(slot, 0, 0)
  return reply
}

/**
 * Reads the block-style YAML GalleryFiles writes (maps, lists and scalars, no flow style) into
 * plain objects: enough to check what a gallery file says.
 */
export function readYaml (text) {
  const lines = text.split('\n').filter((line) => line.trim() && !line.trim().startsWith('#'))
  let i = 0
  const indent = (line) => line.length - line.trimStart().length
  const scalar = (s) => {
    const t = s.trim().replace(/^"(.*)"$/, '$1')
    return /^-?\d+(\.\d+)?$/.test(t) ? Number(t) : t
  }
  function block (depth) {
    if (lines[i].trimStart().startsWith('- ') || lines[i].trim() === '-') {
      const list = []
      while (i < lines.length && indent(lines[i]) === depth && lines[i].trimStart().startsWith('-')) {
        const rest = lines[i].trimStart().slice(1).trimStart()
        if (rest && !/^[\w-]+:( |$)/.test(rest)) {
          list.push(scalar(rest))
          i++
        } else {
          // A map in a list: its first key sits after the dash.
          const at = lines[i].length - rest.length
          lines[i] = ' '.repeat(at) + rest
          list.push(block(at))
        }
      }
      return list
    }
    const map = {}
    while (i < lines.length && indent(lines[i]) === depth && !lines[i].trimStart().startsWith('-')) {
      const [, key, value] = /^\s*([^:]+):\s*(.*)$/.exec(lines[i])
      i++
      if (value === '{}') map[key] = {}
      else if (value !== '') map[key] = scalar(value)
      else if (i < lines.length && (indent(lines[i]) > depth || lines[i].trimStart().startsWith('-'))) map[key] = block(indent(lines[i]))
      else map[key] = null
    }
    return map
  }
  return block(0)
}

/**
 * What a gallery file says about a cell: its bounds as { min: [x, y, z], max: [x, y, z] } and its
 * anchors as [{ type, x, y, z, yaw }].
 */
export function cellInFile (ctx, cell, gallery = 'season-2') {
  const file = readYaml(ctx.readFile(GALLERY_FILE(gallery)))
  const found = file.cells?.[cell]
  assert(found, `${gallery}.yml has cell ${cell}: ${JSON.stringify(Object.keys(file.cells ?? {}))}`)
  return {
    bounds: found.bounds ?? null,
    anchors: (found.anchors ?? []).map((a) => ({ type: a.type, x: a.pos[0], y: a.pos[1], z: a.pos[2], yaw: a.yaw ?? 0 }))
  }
}

/** The ids of a gallery's cells, in file order. */
export function cellIds (ctx, gallery = 'season-2') {
  return Object.keys(readYaml(ctx.readFile(GALLERY_FILE(gallery))).cells ?? {})
}

/** The one anchor of a type in a cell, from the gallery file. */
export function anchorOf (ctx, cell, type, gallery = 'season-2') {
  const found = cellInFile(ctx, cell, gallery).anchors.filter((a) => a.type === type)
  assert(found.length === 1, `${cell} has one ${type} anchor: ${JSON.stringify(found)}`)
  return found[0]
}

/**
 * Saves the box from `from` of `size` as the structure `seasons:<name>` with a real structure
 * block in SAVE mode, powered by a redstone block as a builder could, then takes both away. The
 * structure block stands just below the box's minimum corner. With `entities` the entities in the
 * box are saved too.
 */
export async function saveStructure (ctx, admin, name, from, size, { entities = false } = {}) {
  const block = { x: from.x, y: from.y - 1, z: from.z - 1 }
  const nbt = `{mode:"SAVE",name:"seasons:${name}",posX:0,posY:1,posZ:1,sizeX:${size.x},sizeY:${size.y},sizeZ:${size.z},ignoreEntities:${entities ? '0b' : '1b'}}`
  await ctx.command(admin, `/setblock ${block.x} ${block.y} ${block.z} minecraft:structure_block[mode=save]${nbt}`, /^Changed the block/)
  await ctx.command(admin, `/setblock ${block.x} ${block.y - 1} ${block.z} minecraft:redstone_block`, /^Changed the block/)
  await ctx.sync(admin, [], 2)
  await ctx.command(admin, `/setblock ${block.x} ${block.y - 1} ${block.z} minecraft:stone`, /^Changed the block/)
  await ctx.command(admin, `/setblock ${block.x} ${block.y} ${block.z} minecraft:stone`, /^Changed the block/)
}

/** Places a Seasons marker: a structure block in DATA mode with `seasons:<type>` as its metadata. */
export async function marker (ctx, admin, at, type) {
  await ctx.command(admin, `/setblock ${at.x} ${at.y} ${at.z} minecraft:structure_block[mode=data]{mode:"DATA",metadata:"${type.includes(':') ? type : `seasons:${type}`}"}`, /^Changed the block/)
}

/** Whether any structure block is left in a box, as /fill reports it; any found are cleared. */
export async function structureBlocksIn (ctx, admin, min, max) {
  const line = await ctx.command(admin, `/fill ${min.x} ${min.y} ${min.z} ${max.x} ${max.y} ${max.z} minecraft:air replace minecraft:structure_block`, /^(Successfully filled|No blocks were filled)/)
  return !line.startsWith('No blocks')
}

/** Whether a block is of a type, by /execute if block. */
export function blockIs (ctx, admin, at, type) {
  return ctx.holds(admin, `block ${at.x} ${at.y} ${at.z} ${type}`)
}
