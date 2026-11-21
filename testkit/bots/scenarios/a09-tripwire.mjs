// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A9. An item tagged seasons:archived found in a real inventory or on the ground is deleted
// within 30 s and audited (and posted to the admin webhook: m4-discord). One in
// an item frame, on an armour stand or in a container in the Hall is found as its chunk loads.
import { SECOND, assert } from '../lib/context.mjs'
import { FLOOR, blockItems, selectorData } from '../lib/hall.mjs'
import { reloadChunks } from '../lib/protection.mjs'

const TAGGED = 'minecraft:stone[minecraft:custom_data={PublicBukkitValues:{"seasons:archived":1b}}]'
const TAGGED_ITEM = '{id:"minecraft:stone",count:2,components:{"minecraft:custom_data":{PublicBukkitValues:{"seasons:archived":1b}}}}'
const TAGGED_HELMET = '{id:"minecraft:iron_helmet",count:1,components:{"minecraft:custom_data":{PublicBukkitValues:{"seasons:archived":1b}}}}'

// Far from spawn, so the chunks unload once they aren't forceloaded.
const X = 2000
const Z = 2000

export default {
  name: 'a09-tripwire',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Tripper')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await ctx.command(admin, '/give Tripper minecraft:dirt 5', /^Gave 5/)

    await ctx.command(admin, `/give Tripper ${TAGGED} 3`, /^Gave 3/)
    assert(await ctx.itemCount(admin, 'Tripper') === 8, 'the tagged stone arrived')
    // The inventory sweep runs every 30 seconds by Seasons' clock.
    await ctx.advance(admin, 30 * SECOND)
    await ctx.eventually(async () => {
      assert(await ctx.itemCount(admin, 'Tripper') === 5, 'the tagged stone is gone, the dirt stays')
    }, 32 * SECOND)

    await ctx.command(admin, `/summon minecraft:item 3 -60 3 {Item:${TAGGED_ITEM}}`, /^Summoned new /)
    await ctx.eventually(async () => {
      assert(!await ctx.holds(admin, 'entity @e[type=item]'), 'the tagged item on the ground is gone')
    }, 32 * SECOND)

    // In the world, where only the chunk loading finds them: a frame, a stand and a Hall chest.
    await ctx.command(admin, `/forceload add ${X} ${Z} ${X + 15} ${Z + 15}`, /^(Marked|No chunks were marked)/)
    await ctx.command(admin, '/hof gallery create tripwire 2', /^Created gallery tripwire for Season 2\.$/)
    await ctx.command(admin, `/hof gallery bounds tripwire ${X} ${FLOOR - 1} ${Z} ${X + 15} ${FLOOR + 5} ${Z + 15}`, /^Gallery tripwire now spans /)
    const chest = { x: X + 2, y: FLOOR, z: Z + 2 }
    await ctx.command(admin, `/setblock ${chest.x} ${chest.y} ${chest.z} minecraft:chest`, /^Changed the block/)
    await ctx.command(admin, `/item replace block ${chest.x} ${chest.y} ${chest.z} container.0 with ${TAGGED} 4`, /^Replaced /)
    await ctx.command(admin, `/item replace block ${chest.x} ${chest.y} ${chest.z} container.1 with minecraft:dirt 6`, /^Replaced /)
    await ctx.command(admin, `/setblock ${X + 5} ${FLOOR} ${Z + 6} minecraft:stone`, /^Changed the block/)
    await ctx.command(admin, `/summon minecraft:item_frame ${X + 5} ${FLOOR} ${Z + 5} {Facing:2b,Tags:["tripped"],Item:${TAGGED_ITEM}}`, /^Summoned /)
    await ctx.command(admin, `/summon minecraft:armor_stand ${X + 8.5} ${FLOOR} ${Z + 8.5} {Tags:["tripped"],equipment:{head:${TAGGED_HELMET}}}`, /^Summoned /)
    await reloadChunks(ctx, admin, { x: X, z: Z }, { x: X + 15, z: Z + 15 }, { x: X, y: FLOOR, z: Z })
    await ctx.eventually(async () => {
      assert(await selectorData(ctx, admin, '@e[type=item_frame,tag=tripped,limit=1]', 'Item') === null, 'the frame\'s tagged item is gone')
      assert(await selectorData(ctx, admin, '@e[type=armor_stand,tag=tripped,limit=1]', 'equipment.head') === null, 'the stand\'s tagged helmet is gone')
      const left = await blockItems(ctx, admin, chest)
      assert(/dirt/.test(left ?? '') && !/stone/.test(left), `the Hall chest keeps only its dirt: ${left}`)
    }, 32 * SECOND)

    const month = new Date().toISOString().slice(0, 7)
    const audit = ctx.readFile(`world/dimensions/minecraft/overworld/seasons/audit/${month}.jsonl`).trim().split('\n').map((line) => JSON.parse(line))
    const hits = audit.filter((entry) => entry.action === 'tripwire')
    assert(hits.some((entry) => entry.target === 'Tripper' && /3 × minecraft:stone/.test(entry.details)), `the inventory hit is audited: ${JSON.stringify(hits)}`)
    assert(hits.some((entry) => /on the ground/.test(entry.details) && /2 × minecraft:stone/.test(entry.details)), `the ground hit is audited: ${JSON.stringify(hits)}`)
    assert(hits.some((entry) => /in an item frame/.test(entry.details) && /× minecraft:stone/.test(entry.details)), `the frame hit is audited: ${JSON.stringify(hits)}`)
    assert(hits.some((entry) => /on an armour stand/.test(entry.details) && /minecraft:iron_helmet/.test(entry.details)), `the stand hit is audited: ${JSON.stringify(hits)}`)
    assert(hits.some((entry) => /in a Hall container/.test(entry.details) && /4 × minecraft:stone/.test(entry.details)), `the chest hit is audited: ${JSON.stringify(hits)}`)
  }
}
