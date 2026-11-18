// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A50. Importing a Season 1 cell with an armour stand (gear and player head) and three chests makes
// one record with the same items, one archive page per chest, a mannequin wearing the gear with
// the head's skin, and three archive anchors. The chests and stand hold nothing real afterwards,
// and /hof import undo puts every item back where it was.
import { assert, sleep } from '../lib/context.mjs'
import { at, blockItems, buildSeasonOneCell, selectorData, skinValue, use } from '../lib/hall.mjs'

// Each chest holds diamonds, logs and a shulker box of gold; the stand a chestplate, boots, a
// sword, torches and the head; the frame an emerald.
const CHEST_STACKS = 3
const STAND_STACKS = 5
const REPORT = 'world/dimensions/minecraft/overworld/seasons/import-season-1.txt'

export default {
  name: 'a50-import',
  async run (ctx) {
    const admin = await ctx.admin()
    const owner = await ctx.join('Owner')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    const built = await buildSeasonOneCell(ctx, admin, { x: 60, z: 40, head: 'Owner', chests: 3 })
    const stand = at('minecraft:armor_stand', built.stand)
    const frame = at('minecraft:item_frame', built.frame)
    // One command at a time: replies are matched by their text, not by which command asked.
    const chests = []
    for (const chest of built.chests) chests.push(await blockItems(ctx, admin, chest))
    const before = {
      chests,
      stand: await selectorData(ctx, admin, stand, 'equipment'),
      frame: await selectorData(ctx, admin, frame, 'Item')
    }
    assert(before.chests.every(Boolean) && before.stand && before.frame, `the hand-built cell is full: ${JSON.stringify(before)}`)

    await ctx.command(admin, '/hof gallery create season-1 1', /^Created gallery season-1 for Season 1\.$/)
    await ctx.command(admin, '/hof gallery bounds season-1 58 -61 38 76 -50 50', /^Gallery season-1 now spans /)
    await ctx.command(admin, '/hof cell new season-1 owner', /^Created cell owner in gallery season-1\.$/)
    await ctx.command(admin, '/hof cell bounds season-1:owner 59 -61 39 75 -52 49', /^Cell season-1:owner now spans /)

    // The dry run reports and changes nothing.
    await ctx.command(admin, '/hof import season-1 --dry-run', /^Dry run of the season-1 import: 1 cell ready, 0 flagged\. See import-season-1\.txt\.$/, 20_000)
    const report = ctx.readFile(REPORT)
    ctx.log(report)
    assert(/Cell owner: ready/.test(report), 'the cell is ready')
    assert(/owner: Owner \(.*\), from the player head on the stand/.test(report), 'the owner comes from the head')
    assert((report.match(/chest at /g) ?? []).length === 3, 'three chests are listed')
    assert(/item frame at .*: 1 × minecraft:emerald/.test(report), 'the frame is listed')
    assert(await blockItems(ctx, admin, built.chests[0]) === before.chests[0], 'the dry run moved nothing')

    await ctx.command(admin, '/hof import season-1', /^Imported 1 cell from season-1; 0 skipped\.$/, 20_000)
    const archive = await ctx.archive(admin, 'Owner', 1)
    assert(archive.present, `one record for Owner's Season 1: ${JSON.stringify(archive)}`)
    assert(archive.stacks === 3 * CHEST_STACKS + STAND_STACKS + 1, `the record holds every stack: ${JSON.stringify(archive)}`)
    assert(archive.pages === 4, `a page for the stand and frame, then one per chest: ${JSON.stringify(archive)}`)

    // Nothing real is left: empty chests, no stand, no frame.
    for (const chest of built.chests) {
      assert(await blockItems(ctx, admin, chest) === null, `the chest at ${JSON.stringify(chest)} is empty`)
      assert(await ctx.holds(admin, `block ${chest.x} ${chest.y} ${chest.z} minecraft:chest`), 'and stays as the archive anchor')
    }
    assert(!await ctx.holds(admin, `entity ${stand}`), 'the stand is gone')
    assert(!await ctx.holds(admin, `entity ${frame}`), 'the frame is gone')

    // A mannequin in the stand's place, wearing the gear, with the head's skin.
    const figure = at('minecraft:mannequin', built.stand)
    const gear = await ctx.eventually(async () => {
      const found = await selectorData(ctx, admin, figure, 'equipment')
      assert(found, 'the mannequin stands')
      return found
    })
    for (const item of ['iron_chestplate', 'golden_boots', 'diamond_sword', 'torch']) {
      assert(gear.includes(`minecraft:${item}`), `the mannequin wears the ${item}: ${gear}`)
    }
    const profile = await selectorData(ctx, admin, figure, 'profile')
    assert(profile && profile.includes(skinValue('Owner')), `the mannequin has the head's skin: ${profile}`)
    assert(await ctx.holds(admin, `entity ${at('minecraft:item_display', built.frame)}`), 'the emerald shows as a trophy')
    await ctx.command(admin, '/hof cell info season-1:owner', /^Cell season-1:owner: owner Owner for Season 1; anchors: figure 1, archive 3, trophy 1$/)

    // Each archive anchor opens the view at its own chest's page.
    await ctx.command(admin, `/tp Owner ${built.stand.x + 0.5} -60 ${built.stand.z - 1.5}`, /^Teleported /)
    await sleep(1000)
    for (let i = 0; i < built.chests.length; i++) {
      const opened = new Promise((resolve) => owner.once('windowOpen', resolve))
      await use(ctx, admin, 'Owner', built.chests[i])
      const window = await opened
      assert(/Owner · Season 1/.test(JSON.stringify(window.title)), `titled for the archive: ${JSON.stringify(window.title)}`)
      const diamonds = window.slots[0]
      assert(diamonds?.name === 'diamond' && diamonds.count === i + 1, `chest ${i + 1}'s page starts with its diamonds: ${JSON.stringify(diamonds)}`)
      assert(window.slots[4 + i]?.name === 'oak_log' && window.slots[26]?.name === 'shulker_box', `chest ${i + 1}'s items keep their slots`)
      owner.closeWindow(window)
      await sleep(300)
    }
    // The figure opens the view too, by the bot's own click.
    const opened = new Promise((resolve) => owner.once('windowOpen', resolve))
    const mannequin = Object.values(owner.entities).find((e) => e.name === 'mannequin')
    assert(mannequin, 'the bot sees the mannequin')
    owner.activateEntity(mannequin)
    const window = await opened
    assert(window.slots.slice(0, 45).some((item) => item?.name === 'iron_chestplate'), 'the figure opens the gear page')
    owner.closeWindow(window)
    assert(await ctx.itemCount(admin, 'Owner') === 0, 'looking takes nothing')

    // Undo puts every item back exactly where it came from.
    await ctx.command(admin, '/hof import undo season-1', /^Put back 1 cell of season-1\.$/, 20_000)
    for (let i = 0; i < built.chests.length; i++) {
      assert(await blockItems(ctx, admin, built.chests[i]) === before.chests[i], `chest ${i + 1} holds what it held`)
    }
    assert(await selectorData(ctx, admin, stand, 'equipment') === before.stand, 'the stand wears what it wore')
    assert(await selectorData(ctx, admin, frame, 'Item') === before.frame, 'the frame holds its emerald')
    assert(!await ctx.holds(admin, `entity ${figure}`), 'the mannequin is gone')
    assert(!(await ctx.archive(admin, 'Owner', 1)).present, 'the record is set aside')
  }
}
