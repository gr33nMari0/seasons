// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A8. Every click type in the archive view (left, right, shift, number keys, off-hand swap, drop,
// drag, double-click, creative clone) moves nothing, and the client is resynced. The view pages.
// The view opens from the viewer's archive anchor in their cell.
import itemLoader from 'prismarine-item'
import { assert, sleep } from '../lib/context.mjs'
import { GEAR_ITEMS, GEAR_STACKS } from '../lib/gear.mjs'
import { buildGallery, cellOf, use } from '../lib/hall.mjs'

const VIEW_SLOTS = 45
const FIRST_OWN_SLOT = 54
const HOTBAR_START = 81

export default {
  name: 'a08-archive-view',
  async run (ctx) {
    const admin = await ctx.admin()
    const viewer = await ctx.join('Viewer')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    // With the ender chest archived too, the archive has a second page.
    ctx.writeFile('plugins/Seasons/config.yml', 'schema-version: 1\nresets:\n  enderChest: archive\n')
    await ctx.command(admin, '/season reload', /^Reloaded /)
    const cells = await buildGallery(ctx, admin, { cells: 2 })
    await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: 2 players have cells\.$/)
    const cell = await cellOf(ctx, admin, 'season-2', cells, 'Viewer')
    await ctx.command(admin, '/item replace entity Viewer hotbar.0 with minecraft:diamond_sword', /^Replaced \d+ slot/)
    await ctx.command(admin, '/item replace entity Viewer hotbar.1 with minecraft:dirt 64', /^Replaced \d+ slot/)
    await ctx.command(admin, '/item replace entity Viewer inventory.4 with minecraft:torch 32', /^Replaced \d+ slot/)
    await ctx.command(admin, '/item replace entity Viewer armor.head with minecraft:iron_helmet', /^Replaced \d+ slot/)
    await ctx.command(admin, '/item replace entity Viewer weapon.offhand with minecraft:shield', /^Replaced \d+ slot/)
    await ctx.command(admin, '/item replace entity Viewer enderchest.0 with minecraft:emerald 5', /^Replaced \d+ slot/)
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
    const archived = await ctx.archive(admin, 'Viewer', 2)
    assert(archived.items === GEAR_ITEMS + 5 && archived.stacks === GEAR_STACKS + 1, `the archive holds gear and ender chest: ${JSON.stringify(archived)}`)

    // Something of the viewer's own, so clicks in their inventory have something to move.
    await ctx.command(admin, '/item replace entity Viewer hotbar.2 with minecraft:dirt 10', /^Replaced \d+ slot/)

    const window = await openView(ctx, admin, viewer, cell)
    assert(window.slots.length >= 90, `a 54-slot view, saw ${window.slots.length} slots`)
    const before = snapshot(window)
    assert(before.view.filter(Boolean).length === GEAR_STACKS, `page 1 shows the gear: ${JSON.stringify(before.view)}`)
    const sword = before.view.findIndex((item) => item?.name === 'diamond_sword')
    const dirt = before.view.findIndex((item) => item?.name === 'dirt')
    assert(sword >= 0 && dirt >= 0, 'the sword and dirt are on page 1')

    const clicks = {
      'left click': () => viewer.clickWindow(sword, 0, 0),
      'right click': () => viewer.clickWindow(dirt, 1, 0),
      'shift click': () => viewer.clickWindow(sword, 0, 1),
      'number key 3': () => viewer.clickWindow(sword, 2, 2),
      // Mineflayer refuses button 40 ("invalid operation"), so the swap goes as a raw packet.
      'off-hand swap': () => raw(viewer, window, [[sword, 40, 2]]),
      'drop one (Q)': () => viewer.clickWindow(dirt, 0, 4),
      'drop stack (Ctrl+Q)': () => viewer.clickWindow(dirt, 1, 4),
      'shift click from own inventory': () => viewer.clickWindow(HOTBAR_START + 2, 0, 1),
      drag: () => raw(viewer, window, [[-999, 0, 5], [dirt, 1, 5], [sword, 1, 5], [-999, 2, 5]]),
      'double click to collect': () => raw(viewer, window, [[HOTBAR_START + 2, 0, 0], [HOTBAR_START + 2, 0, 6]])
    }
    for (const [name, click] of Object.entries(clicks)) {
      await expectNothingMoved(ctx, admin, viewer, before, name, click)
    }
    await ctx.command(admin, '/gamemode creative Viewer', /^Set Viewer's game mode/)
    await expectNothingMoved(ctx, admin, viewer, before, 'creative clone', () => viewer.clickWindow(sword, 2, 3))
    await ctx.command(admin, '/gamemode survival Viewer', /^Set Viewer's game mode/)

    // Paging: Next shows the ender chest page; Previous comes back.
    await viewer.clickWindow(53, 0, 0)
    await ctx.eventually(async () => {
      const page = snapshot(viewer.currentWindow)
      assert(page.view.some((item) => item?.name === 'emerald'), `page 2 shows the ender chest: ${JSON.stringify(page.view)}`)
    })
    await viewer.clickWindow(45, 0, 0)
    await ctx.eventually(async () => {
      const page = snapshot(viewer.currentWindow)
      assert(page.view.some((item) => item?.name === 'diamond_sword'), 'Previous goes back to page 1')
    })
    viewer.closeWindow(viewer.currentWindow)
    await sleep(500)
    assert(await ctx.itemCount(admin, 'Viewer') === 10, 'the viewer still has only their own dirt')
    assert((await ctx.archive(admin, 'Viewer', 2)).items === GEAR_ITEMS + 5, 'the archive is unchanged')
  }
}

async function openView (ctx, admin, viewer, cell) {
  const opened = new Promise((resolve) => viewer.once('windowOpen', resolve))
  await use(ctx, admin, 'Viewer', cell.archive)
  const window = await opened
  assert(/Viewer · Season 2/.test(JSON.stringify(window.title)), `the view is titled for the archive: ${JSON.stringify(window.title)}`)
  return window
}

/** The item names and counts in the view's item slots and the viewer's own slots. */
function snapshot (window) {
  const describe = (item) => item ? { name: item.name, count: item.count } : null
  return {
    view: window.slots.slice(0, VIEW_SLOTS).map(describe),
    own: window.slots.slice(FIRST_OWN_SLOT).map(describe)
  }
}

/** Sends clicks as raw packets, for the click types Mineflayer doesn't model or refuses. */
async function raw (bot, window, clicks) {
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

async function expectNothingMoved (ctx, admin, viewer, before, name, click) {
  ctx.log(`click: ${name}`)
  // A click that throws was never sent, so it would prove nothing: that fails the scenario.
  await click()
  await viewer._syncWindow(viewer.currentWindow)
  const after = snapshot(viewer.currentWindow)
  assert(JSON.stringify(after.view) === JSON.stringify(before.view), `${name}: the view is unchanged: ${JSON.stringify(after.view)}`)
  assert(JSON.stringify(after.own) === JSON.stringify(before.own), `${name}: the viewer's inventory is unchanged: ${JSON.stringify(after.own)}`)
  assert(viewer.currentWindow.selectedItem == null, `${name}: nothing on the cursor`)
  assert(await ctx.itemCount(admin, 'Viewer') === 10, `${name}: the viewer holds only their own dirt`)
  assert(!await ctx.holds(admin, 'entity @e[type=item]'), `${name}: nothing was dropped`)
}
