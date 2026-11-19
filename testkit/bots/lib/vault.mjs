// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The ender vault in scenarios: an ender chest worth sealing, opening /vault, reading its pages and
// clicking in it.
import itemLoader from 'prismarine-item'
import { assert } from './context.mjs'

/** Item slots on a vault page; the sixth row holds the page buttons. */
export const VIEW_SLOTS = 45
/** The first of the viewer's own slots in a six-row menu. */
export const FIRST_OWN_SLOT = 54
export const HOTBAR_START = 81
export const PREVIOUS = 45
export const NEXT = 53

/** Five emeralds, a music disc and the dragon egg: everyday items and collectibles. */
export const ENDER = [['emerald', 5], ['music_disc_cat', 1], ['dragon_egg', 1]]
export const ENDER_COUNT = 7

/** Fills a player's ender chest with ENDER, from its first slot. */
export async function fillEnderChest (ctx, admin, name) {
  for (const [i, [item, count]] of ENDER.entries()) {
    await ctx.command(admin, `/item replace entity ${name} enderchest.${i} with minecraft:${item} ${count}`, /^Replaced \d+ slot/)
  }
}

/** Runs /vault as a bot, waits for the reply and the menu, and resolves with the menu. */
export async function openVault (ctx, bot, expect) {
  const opened = new Promise((resolve) => bot.once('windowOpen', resolve))
  await ctx.command(bot, '/vault', expect)
  const window = await opened
  assert(/Ender vault/.test(JSON.stringify(window.title)), `the vault is titled: ${JSON.stringify(window.title)}`)
  return window
}

/** The names and counts in a menu's item slots and in the viewer's own slots. */
export function snapshot (window) {
  const describe = (item) => item ? { name: item.name, count: item.count } : null
  return {
    view: window.slots.slice(0, VIEW_SLOTS).map(describe),
    own: window.slots.slice(FIRST_OWN_SLOT).map(describe)
  }
}

/** The slot of the first item with a name on the page shown, or -1. */
export function slotOf (window, name) {
  return window.slots.slice(0, VIEW_SLOTS).findIndex((item) => item?.name === name)
}

/** How many of an item a player carries, as /clear counts them. */
export async function carried (ctx, admin, name, item) {
  const line = await ctx.command(admin, `/clear ${name} minecraft:${item} 0`, /^(Found \d+ matching item|No items were found on player)/)
  const match = /^Found (\d+) matching item/.exec(line)
  return match ? Number(match[1]) : 0
}

/** Sends clicks as raw packets, for the click types Mineflayer doesn't model or refuses. */
export async function raw (bot, window, clicks) {
  const Item = itemLoader(bot.registry)
  for (const [slot, mouseButton, mode] of clicks) {
    bot._client.write('window_click', {
      windowId: window.id,
      stateId: -1,
      slot,
      mouseButton,
      mode,
      changedSlots: [],
      cursorItem: Item.toNotch(null)
    })
  }
  await bot._syncWindow(window)
}

/**
 * Every click that could move an item, on an item slot of the page shown and from the viewer's
 * own inventory, as [name, click].
 */
export function everyClick (bot, window, slot, own) {
  return [
    ['left click', () => bot.clickWindow(slot, 0, 0)],
    ['right click', () => bot.clickWindow(slot, 1, 0)],
    ['shift click', () => bot.clickWindow(slot, 0, 1)],
    ['number key 3', () => bot.clickWindow(slot, 2, 2)],
    ['off-hand swap', () => raw(bot, window, [[slot, 40, 2]])],
    ['drop one (Q)', () => bot.clickWindow(slot, 0, 4)],
    ['drop stack (Ctrl+Q)', () => bot.clickWindow(slot, 1, 4)],
    ['shift click from own inventory', () => bot.clickWindow(own, 0, 1)],
    ['drag', () => raw(bot, window, [[-999, 0, 5], [slot, 1, 5], [slot + 1, 1, 5], [-999, 2, 5]])],
    ['double click to collect', () => raw(bot, window, [[own, 0, 0], [own, 0, 6]])]
  ]
}

/**
 * Tries every click on a read-only page and checks nothing moved: the page, the viewer's own
 * slots, what they carry, and the ground.
 */
export async function expectReadOnly (ctx, admin, bot, slot, own) {
  const window = bot.currentWindow
  const before = snapshot(window)
  const holding = await ctx.itemCount(admin, bot.username)
  for (const [name, click] of everyClick(bot, window, slot, own)) {
    ctx.log(`click: ${name}`)
    await click()
    await bot._syncWindow(bot.currentWindow)
    await ctx.sync(admin, [bot], 2)
    const after = snapshot(bot.currentWindow)
    assert(JSON.stringify(after.view) === JSON.stringify(before.view), `${name}: the page is unchanged: ${JSON.stringify(after.view)}`)
    assert(JSON.stringify(after.own) === JSON.stringify(before.own), `${name}: the viewer's slots are unchanged: ${JSON.stringify(after.own)}`)
    assert(bot.currentWindow.selectedItem == null, `${name}: nothing on the cursor`)
    assert(await ctx.itemCount(admin, bot.username) === holding, `${name}: ${bot.username} took nothing`)
    assert(!await ctx.holds(admin, 'entity @e[type=item]'), `${name}: nothing was dropped`)
  }
}

/** Clicks one page button and resolves once the page shows an item with a name. */
export async function turnTo (ctx, bot, button, name) {
  await bot.clickWindow(button, 0, 0)
  await ctx.eventually(async () => assert(slotOf(bot.currentWindow, name) >= 0, `the page shows ${name}: ${JSON.stringify(snapshot(bot.currentWindow).view)}`))
}

/**
 * Left-clicks an item on the page shown to take it, and resolves once the player carries `count`
 * of it in all and the vault has opened again, so the next click lands in the new menu.
 */
export async function take (ctx, admin, bot, item, count) {
  const window = bot.currentWindow
  const slot = slotOf(window, item)
  assert(slot >= 0, `the page shows ${item}: ${JSON.stringify(snapshot(window).view)}`)
  const reopened = new Promise((resolve) => bot.once('windowOpen', resolve))
  await bot.clickWindow(slot, 0, 0)
  await ctx.eventually(async () => assert(await carried(ctx, admin, bot.username, item) === count, `${bot.username} carries ${count} ${item}`))
  await reopened
}
