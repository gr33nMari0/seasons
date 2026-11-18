// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A11. /season unarchive restores items and advancements and removes the displays, and needs an
// empty inventory unless --force.
import { assert } from '../lib/context.mjs'
import { ENDER_ITEMS, GEAR_ITEMS, giveGear, giveProgress } from '../lib/gear.mjs'
import { at, buildGallery, cellOf } from '../lib/hall.mjs'

export default {
  name: 'a11-unarchive',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Undo')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await giveGear(ctx, admin, 'Undo')
    await giveProgress(ctx, admin, 'Undo')
    const cells = await buildGallery(ctx, admin, { cells: 2 })
    await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: /)
    const cell = await cellOf(ctx, admin, 'season-2', cells, 'Undo')
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
    assert(await ctx.itemCount(admin, 'Undo') === 0, 'archived')
    const figure = `entity ${at('minecraft:mannequin', cell.figure)}`
    const plaque = `entity ${at('minecraft:text_display', cell.plaque)}`
    await ctx.eventually(async () => assert(await ctx.holds(admin, figure) && await ctx.holds(admin, plaque), 'the figure and plaque stand in the cell'))

    await ctx.command(admin, '/season unarchive Nobody 2', /^Nobody isn't online\b/)
    await ctx.command(admin, '/season unarchive Undo 1', /^Undo has no archive for Season 1\.$/)
    await ctx.command(admin, '/give Undo minecraft:apple 2', /^Gave 2/)
    await ctx.command(admin, '/season unarchive Undo 2', /^Undo's inventory isn't empty\b/)
    assert(await ctx.itemCount(admin, 'Undo') === 2, 'a refused unarchive changes nothing')

    await ctx.command(admin, '/season unarchive Undo 2 --force', /^Restored Undo's Season 2 archive to them\b/)
    await ctx.eventually(async () => assert(await ctx.itemCount(admin, 'Undo') === GEAR_ITEMS, 'the gear is back'))
    assert(/emerald/.test(await ctx.entityData(admin, 'Undo', 'EnderItems')), 'the ender chest is back')
    assert(await ctx.holds(admin, 'entity @a[name=Undo,advancements={minecraft:story/mine_stone=true}]'), 'Stone Age is back')
    const record = await ctx.archive(admin, 'Undo', 2)
    assert(!record.present && record.archivedThrough === 1, `the archive is withdrawn: ${JSON.stringify(record)}`)
    assert(record.vault === 0, 'the vault page is withdrawn')
    assert(!await ctx.holds(admin, figure) && !await ctx.holds(admin, plaque), 'the figure and plaque are gone')

    // The apples that were in the way are kept in a displaced-items file, never destroyed.
    const month = new Date().toISOString().slice(0, 7)
    const audit = ctx.readFile(`world/dimensions/minecraft/overworld/seasons/audit/${month}.jsonl`).trim().split('\n').map((line) => JSON.parse(line))
    const entry = audit.find((e) => e.action === 'unarchive' && e.target === 'Undo')
    assert(entry && /displaced 2 items/.test(entry.details), `the unarchive is audited with the displaced items: ${JSON.stringify(entry)}`)

    // Archiving again works, and catches the same gear.
    await ctx.command(admin, '/season archive Undo', /^Archived Undo into Season 2\.$/, 20_000)
    const again = await ctx.archive(admin, 'Undo', 2)
    assert(again.items === GEAR_ITEMS && again.vault === ENDER_ITEMS, `archived again: ${JSON.stringify(again)}`)
    await ctx.eventually(async () => assert(await ctx.holds(admin, figure), 'the figure is back'))
  }
}
