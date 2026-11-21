// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The curator view: opened with /hof curate, or by sneak-clicking a cell's archive anchor or
// figure with seasons.curate.view; without sneaking, or without the node, the same clicks open
// the ordinary archive view. It shows what the archive view shows, and no click moves anything
// out of it or into it; a click on an item only opens the action menu, which offers the actions
// the curator holds a node for. A player without seasons.curate.view can't run /hof curate, and
// one with only it may look but not act. Unknown players and seasons are refused.
import { assert } from '../lib/context.mjs'
import { GEAR_ITEMS, actions, archiveWithGear, openCurate, pick } from '../lib/curate.mjs'
import { use } from '../lib/hall.mjs'
import { HOTBAR_START, everyClick, snapshot } from '../lib/vault.mjs'

export default {
  name: 'm4-curate-view',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Owner')
    const looker = await ctx.join('Looker')
    const cells = await archiveWithGear(ctx, admin, ['Owner'])
    const cell = cells.Owner

    await ctx.command(admin, '/hof curate Nobody 2', /^Nobody called Nobody has played here\.$/)
    await ctx.command(admin, '/hof curate Owner 5', /^Owner has no archive for Season 5\.$/)

    // By command: the archive's items, on the same pages as the archive view.
    let window = await openCurate(ctx, admin, 'Owner')
    const items = snapshot(window).view.filter(Boolean)
    assert(items.length === 5, `the curator view shows the gear: ${JSON.stringify(items)}`)
    admin.closeWindow(window)

    // No click moves anything, but a left or shift click on an item opens the action menu.
    await ctx.command(admin, '/clear SeasonsAdmin', /./)
    await ctx.command(admin, '/item replace entity SeasonsAdmin hotbar.2 with minecraft:dirt 10', /^Replaced \d+ slot/)
    window = await openCurate(ctx, admin, 'Owner')
    const sword = window.slots.slice(0, 45).findIndex((item) => item?.name === 'diamond_sword')
    const before = snapshot(window)
    for (const [name, click] of everyClick(admin, window, sword, HOTBAR_START + 2)) {
      if (name === 'left click' || name === 'shift click') continue
      ctx.log(`click: ${name}`)
      await click()
      await admin._syncWindow(admin.currentWindow)
      await ctx.sync(admin, [], 2)
      const after = snapshot(admin.currentWindow)
      assert(JSON.stringify(after.view) === JSON.stringify(before.view), `${name}: the view is unchanged`)
      assert(JSON.stringify(after.own) === JSON.stringify(before.own), `${name}: the curator's slots are unchanged`)
      assert(admin.currentWindow.selectedItem == null, `${name}: nothing on the cursor`)
      assert(await ctx.itemCount(admin, 'SeasonsAdmin') === 10, `${name}: the curator took nothing`)
    }
    const menu = await pick(ctx, admin, 'diamond_sword')
    assert(JSON.stringify(actions(menu)) === JSON.stringify(['museum', 'return', 'take', 'destroy', 'cancel']), `an op is offered every action: ${actions(menu)}`)
    // Shift-clicking the curator's own dirt, or right-clicking an action, moves nothing either.
    await admin.clickWindow(menu.inventoryStart + 27 + 2, 0, 1)
    await admin.clickWindow(0, 1, 0)
    await admin._syncWindow(admin.currentWindow)
    await ctx.sync(admin, [], 2)
    assert(await ctx.itemCount(admin, 'SeasonsAdmin') === 10, 'the action menu gives nothing either')
    assert(admin.currentWindow.selectedItem == null, 'nothing on the cursor in the action menu')
    admin.closeWindow(admin.currentWindow)
    assert((await ctx.archive(admin, 'Owner', 2)).items === GEAR_ITEMS, 'the archive is unchanged')

    // Sneak-clicking the archive anchor opens the curator view; a plain click the archive view.
    let opened = new Promise((resolve) => admin.once('windowOpen', resolve))
    await ctx.command(admin, `/scenario use SeasonsAdmin ${cell.archive.x} ${cell.archive.y} ${cell.archive.z} sneak`, /^Used /)
    window = await opened
    assert(/Curating Owner · Season 2/.test(JSON.stringify(window.title)), `sneaking opens the curator view: ${JSON.stringify(window.title)}`)
    admin.closeWindow(window)
    opened = new Promise((resolve) => admin.once('windowOpen', resolve))
    await use(ctx, admin, 'SeasonsAdmin', cell.archive)
    window = await opened
    assert(/Owner · Season 2/.test(JSON.stringify(window.title)) && !/Curating/.test(JSON.stringify(window.title)), `a plain click opens the archive view: ${JSON.stringify(window.title)}`)
    admin.closeWindow(window)

    // Sneak-clicking the figure, as the bot itself does; the test plugin sets the sneaking, which
    // a bot standing still doesn't send (docs/adr/0003).
    await ctx.command(admin, `/tp SeasonsAdmin ${cell.figure.x + 0.5} ${cell.figure.y} ${cell.figure.z - 1.5}`, /^Teleported /)
    const figure = await ctx.eventually(async () => {
      const found = Object.values(admin.entities).find((e) => e.name === 'mannequin' && Math.floor(e.position.x) === cell.figure.x && Math.floor(e.position.z) === cell.figure.z)
      assert(found, 'the admin sees the figure')
      return found
    })
    await ctx.command(admin, '/scenario sneak SeasonsAdmin on', /^SeasonsAdmin is sneaking$/)
    opened = new Promise((resolve) => admin.once('windowOpen', resolve))
    await admin.activateEntity(figure)
    window = await opened
    await ctx.command(admin, '/scenario sneak SeasonsAdmin off', /^SeasonsAdmin isn't sneaking$/)
    assert(/Curating Owner · Season 2/.test(JSON.stringify(window.title)), `sneak-clicking the figure opens the curator view: ${JSON.stringify(window.title)}`)
    admin.closeWindow(window)

    // Without seasons.curate.view: no command, and sneaking opens the archive view.
    await ctx.command(looker, '/hof curate Owner 2', /^(Unknown or incomplete command|Incorrect argument for command)/)
    opened = new Promise((resolve) => looker.once('windowOpen', resolve))
    await ctx.command(admin, `/scenario use Looker ${cell.archive.x} ${cell.archive.y} ${cell.archive.z} sneak`, /^Used /)
    window = await opened
    assert(!/Curating/.test(JSON.stringify(window.title)), `without the node it is the archive view: ${JSON.stringify(window.title)}`)
    looker.closeWindow(window)

    // With view alone: they look, and are told they may not act.
    await ctx.command(admin, '/scenario permit Looker seasons.curate.view', /^Permitted /)
    window = await openCurate(ctx, looker, 'Owner')
    const slot = window.slots.slice(0, 45).findIndex((item) => item?.name === 'diamond_sword')
    const refused = ctx.waitForMessages(looker, /^You may look at this archive, but not move anything out of it\.$/)
    await looker.clickWindow(slot, 0, 0)
    await refused
    looker.closeWindow(looker.currentWindow)

    // With one action's node, only that action is offered.
    await ctx.command(admin, '/scenario permit Looker seasons.curate.return', /^Permitted /)
    await openCurate(ctx, looker, 'Owner')
    const offered = await pick(ctx, looker, 'diamond_sword')
    assert(JSON.stringify(actions(offered)) === JSON.stringify(['return', 'cancel']), `only return is offered: ${actions(offered)}`)
    looker.closeWindow(offered)
    assert((await ctx.archive(admin, 'Owner', 2)).items === GEAR_ITEMS, 'the archive is still unchanged')
  }
}
