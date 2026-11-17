// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A9. An item tagged seasons:archived found in a real inventory or on the ground is deleted
// within 30 s and audited. (Reporting it to the admin webhook waits for M4; it is logged.)
import { SECOND, assert } from '../lib/context.mjs'

const TAGGED = 'minecraft:stone[minecraft:custom_data={PublicBukkitValues:{"seasons:archived":1b}}]'
const TAGGED_ITEM = '{id:"minecraft:stone",count:2,components:{"minecraft:custom_data":{PublicBukkitValues:{"seasons:archived":1b}}}}'

export default {
  name: 'a09-tripwire',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Tripper')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await ctx.command(admin, '/give Tripper minecraft:dirt 5', /^Gave 5/)

    await ctx.command(admin, `/give Tripper ${TAGGED} 3`, /^Gave 3/)
    assert(await ctx.itemCount(admin, 'Tripper') === 8, 'the tagged stone arrived')
    await ctx.eventually(async () => {
      assert(await ctx.itemCount(admin, 'Tripper') === 5, 'the tagged stone is gone, the dirt stays')
    }, 32 * SECOND)

    await ctx.command(admin, `/summon minecraft:item 3 -60 3 {Item:${TAGGED_ITEM}}`, /^Summoned new /)
    await ctx.eventually(async () => {
      assert(!await ctx.holds(admin, 'entity @e[type=item]'), 'the tagged item on the ground is gone')
    }, 32 * SECOND)

    const month = new Date().toISOString().slice(0, 7)
    const audit = ctx.readFile(`world/dimensions/minecraft/overworld/seasons/audit/${month}.jsonl`).trim().split('\n').map((line) => JSON.parse(line))
    const hits = audit.filter((entry) => entry.action === 'tripwire')
    assert(hits.some((entry) => entry.target === 'Tripper' && /3 × minecraft:stone/.test(entry.details)), `the inventory hit is audited: ${JSON.stringify(hits)}`)
    assert(hits.some((entry) => /on the ground/.test(entry.details) && /2 × minecraft:stone/.test(entry.details)), `the ground hit is audited: ${JSON.stringify(hits)}`)
  }
}
