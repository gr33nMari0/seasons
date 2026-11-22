// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Security review H1. What sits in an anvil's, a stonecutter's or a villager's input slots when a
// player is archived is archived with the rest, never handed back once the inventory is cleared:
// even with a full inventory and an item on the cursor, which keeps the menu open.
import { assert } from '../lib/context.mjs'
import { carried } from '../lib/vault.mjs'

/** A full inventory: 36 stacks of stone. */
const FULL = 36 * 64

const PLAYERS = [
  { name: 'Alex', kind: 'anvil', item: 'diamond', inputs: 2 },
  { name: 'Sam', kind: 'stonecutter', item: 'emerald', inputs: 1 },
  { name: 'Pat', kind: 'merchant', item: 'gold_ingot', inputs: 2 }
]

export default {
  name: 'sec-workstation-inputs',
  async run (ctx) {
    const admin = await ctx.admin()
    for (const { name } of PLAYERS) await ctx.join(name)
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)

    for (const { name, kind, item, inputs } of PLAYERS) {
      await ctx.command(admin, `/give ${name} minecraft:stone ${FULL}`, /^Gave /)
      await ctx.command(admin, `/scenario workstation ${name} ${kind} minecraft:${item} 64`, new RegExp(`^Opened a ${kind} for ${name} with ${inputs} input`))
      await ctx.command(admin, `/scenario cursor ${name} minecraft:apple 1`, /^Put 1 × minecraft:apple on the cursor/)
    }

    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\. Welcome to the Intermission\.$/, 20_000)

    for (const { name, item, inputs } of PLAYERS) {
      await ctx.eventually(async () => assert(await ctx.itemCount(admin, name) === 0, `${name} carries nothing after the archive`))
      assert(await carried(ctx, admin, name, item) === 0, `${name} got no ${item} back from the menu`)
      assert(!await ctx.holds(admin, `entity @e[type=item,nbt={Item:{id:"minecraft:${item}"}}]`), `no ${item} was dropped for ${name}`)
      const archive = await ctx.archive(admin, name, 2)
      const expected = FULL + 1 + inputs * 64
      assert(archive.items === expected, `${name}'s archive holds the stone, the apple and the ${item}: ${JSON.stringify(archive)}`)
      assert(archive.archivedThrough === 2, `${name} is archived through Season 2`)
    }
  }
}
