// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Curation in scenarios: an archived owner with the standard gear, the curator view, the action
// menu, the museum plinth picker and the reason typed in chat.
import { assert } from './context.mjs'
import { GEAR_ITEMS, giveGear } from './gear.mjs'
import { at, buildGallery, cellOf, selectorData } from './hall.mjs'

export { GEAR_ITEMS }

/** The icon each action has in the action menu. */
export const ICONS = {
  museum: 'item_frame',
  return: 'ender_chest',
  take: 'hopper',
  destroy: 'lava_bucket',
  cancel: 'barrier'
}

/** Item slots of the curator view; the sixth row holds the page buttons. */
const VIEW_SLOTS = 45

/**
 * Season 2 recorded, a gallery with a cell for each of `names`, each given the standard gear
 * (lib/gear.mjs), and the season ended so everyone online is archived, with cells to spare for
 * the admin and onlookers. Resolves with each name's cell.
 */
export async function archiveWithGear (ctx, admin, names, { cells = names.length + 3 } = {}) {
  await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
  const built = await buildGallery(ctx, admin, { cells })
  for (const name of names) {
    await giveGear(ctx, admin, name)
  }
  await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: /)
  const found = {}
  for (const name of names) {
    found[name] = await cellOf(ctx, admin, 'season-2', built, name)
  }
  await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
  await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
  for (const name of names) {
    await ctx.eventually(async () => assert((await ctx.archive(admin, name, 2)).items === GEAR_ITEMS, `${name} is archived with the gear`))
  }
  return found
}

/**
 * Resolves once a menu that just opened shows its items: the game sends them after the window
 * itself, so a busy server can open the window a moment before it is filled.
 */
async function filled (ctx, bot, slots) {
  await ctx.eventually(async () => assert(shown(bot.currentWindow, slots).length > 0, 'the menu shows its items'))
  return bot.currentWindow
}

/** Runs /hof curate as a bot and resolves with the curator view once it opens and is filled. */
export async function openCurate (ctx, bot, owner, season = 2) {
  const opened = new Promise((resolve) => bot.once('windowOpen', resolve))
  await ctx.command(bot, `/hof curate ${owner} ${season}`, new RegExp(`^Curating ${owner}'s Season ${season} archive\\.`))
  const window = await opened
  assert(new RegExp(`Curating ${owner} · Season ${season}`).test(JSON.stringify(window.title)), `the curator view is titled: ${JSON.stringify(window.title)}`)
  return filled(ctx, bot, VIEW_SLOTS)
}

/** The slot of the first item with a name in a menu's item slots, or -1. */
export function slotOf (window, name, slots = VIEW_SLOTS) {
  return window.slots.slice(0, slots).findIndex((item) => item?.name === name)
}

/** The item names a menu shows, in slot order, leaving out empty slots. */
export function shown (window, slots = VIEW_SLOTS) {
  return window.slots.slice(0, Math.min(slots, window.inventoryStart ?? slots)).filter(Boolean).map((item) => item.name)
}

/** Clicks an item in the curator view and resolves with the action menu that opens. */
export async function pick (ctx, bot, item) {
  const window = bot.currentWindow
  const slot = slotOf(window, item)
  assert(slot >= 0, `the curator view shows ${item}: ${shown(window)}`)
  const opened = new Promise((resolve) => bot.once('windowOpen', resolve))
  await bot.clickWindow(slot, 0, 0)
  const menu = await opened
  assert(/what happens to it\?/.test(JSON.stringify(menu.title)), `the action menu is titled: ${JSON.stringify(menu.title)}`)
  return filled(ctx, bot, 9)
}

/** The actions an action menu offers, by name, from their icons. */
export function actions (menu) {
  const names = Object.fromEntries(Object.entries(ICONS).map(([action, icon]) => [icon, action]))
  return shown(menu, 9).map((icon) => names[icon] ?? icon)
}

/**
 * Clicks an action in the action menu. For the museum, resolves with the plinth picker that
 * opens; otherwise once the reason is asked for (or, with `asked: false`, at once).
 */
export async function choose (ctx, bot, action, { asked = true } = {}) {
  const menu = bot.currentWindow
  const slot = slotOf(menu, ICONS[action], 9)
  assert(slot >= 0, `the action menu offers ${action}: ${actions(menu)}`)
  if (action === 'museum') {
    const opened = new Promise((resolve) => bot.once('windowOpen', resolve))
    await bot.clickWindow(slot, 0, 0)
    const picker = await opened
    assert(/Which museum plinth\?/.test(JSON.stringify(picker.title)), `the plinth picker is titled: ${JSON.stringify(picker.title)}`)
    return filled(ctx, bot, 9)
  }
  const prompt = asked ? ctx.waitForMessages(bot, /^Type the reason/) : null
  await bot.clickWindow(slot, 0, 0)
  if (prompt) await prompt
  return null
}

/** Picks the n-th free plinth (0-based) in the plinth picker, and waits for the reason prompt. */
export async function plinth (ctx, bot, index = 0) {
  const prompt = ctx.waitForMessages(bot, /^Type the reason/)
  await bot.clickWindow(index, 0, 0)
  await prompt
}

/** Types the reason in chat and resolves with the curator's reply. */
export async function reason (ctx, bot, text, expect) {
  const reply = ctx.waitForMessages(bot, expect)
  bot.chat(text)
  return reply
}

/** The whole of one action: open the view, pick the item, choose, give the reason. */
export async function curate (ctx, bot, owner, item, action, why, expect, { anchor = 0 } = {}) {
  await openCurate(ctx, bot, owner)
  await pick(ctx, bot, item)
  await choose(ctx, bot, action)
  if (action === 'museum') await plinth(ctx, bot, anchor)
  return reason(ctx, bot, why, expect)
}

/** What a cell's figure wears, as /data prints its equipment, or null while there is no figure. */
export async function figureGear (ctx, admin, cell) {
  return selectorData(ctx, admin, at('minecraft:mannequin', cell.figure), 'equipment')
}

/** The audit lines /season audit prints for a name, joined. */
export async function auditText (ctx, admin, name) {
  const lines = []
  const collect = (text) => lines.push(text)
  admin.on('messagestr', collect)
  await ctx.command(admin, `/season audit ${name}`, /^The last \d+ audit entries for /)
  await ctx.sync(admin)
  admin.off('messagestr', collect)
  return lines.join('\n')
}
