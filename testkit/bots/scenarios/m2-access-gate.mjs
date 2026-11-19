// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// Access mode gate (questions 169): the gate plane is iron bars while the cell is locked and air
// while its owner may decorate it. The owner inside when it shuts goes to the gallery entry; a
// visitor can't break it; a gate changed by /setblock is put back and audited; it is never set over
// a chest, which keeps its items; after a restart it is still shut.
import { assert } from '../lib/context.mjs'
import { GATE, gateGallery, waitGate } from '../lib/gates.mjs'
import { FLOOR, breakBlock, inside } from '../lib/hall.mjs'
import { audit } from '../lib/protection.mjs'
import { move, standAt, waitNear } from '../lib/visits.mjs'

const BARS = 'minecraft:iron_bars'
const AIR = 'minecraft:air'

export default {
  name: 'm2-access-gate',
  sessions: [
    async (ctx) => {
      const admin = await ctx.admin()
      const owner = await ctx.join('Owner')
      await ctx.join('Visitor')
      const [c1] = await gateGallery(ctx, admin)
      await waitGate(ctx, admin, BARS)

      await ctx.command(admin, '/hof cell assign season-2:c1 Owner', /^Assigned season-2:c1 to Owner for Season 2\.$/)
      await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: /)
      await waitGate(ctx, admin, AIR)

      // The owner walks in through the open gate.
      await standAt(ctx, admin, 'Owner', { x: 22, y: FLOOR, z: GATE.z - 1 })
      await move(ctx, admin, 'Owner', { x: 22.5, y: FLOOR, z: GATE.z + 3.5 }, 'walk')
      await ctx.eventually(async () => assert(inside(c1, await ctx.position(admin, 'Owner')), 'the owner is in their cell'))

      // Closing the window shuts it, with the owner put out at the gallery entry.
      const told = ctx.waitForMessages(owner, /^The cell's gate shut, so you're at the gallery entry\.$/)
      await ctx.command(admin, '/season decorate close', /^The decorating window for Season 2 is closed\.$/)
      await waitGate(ctx, admin, BARS)
      await told
      await waitNear(ctx, admin, 'Owner', { x: 21.5, y: FLOOR, z: 21.5 })

      // Nobody breaks it.
      await standAt(ctx, admin, 'Visitor', { x: 22, y: FLOOR, z: GATE.z - 1 })
      assert(!await breakBlock(ctx, admin, 'Visitor', { x: 22, y: FLOOR, z: GATE.z }), 'a visitor can\'t break the gate')

      // A gate changed behind Seasons' back is put back.
      await ctx.command(admin, `/setblock 22 ${FLOOR} ${GATE.z} ${AIR}`, /^Changed the block/)
      await waitGate(ctx, admin, BARS)
      const repairs = audit(ctx).filter((e) => e.action === 'reconcile' && e.target === c1.key)
      assert(repairs.some((e) => /gate/.test(e.details)), `the repair is audited: ${JSON.stringify(repairs)}`)

      // A gate is never set over a block with contents: the chest keeps its diamond, admins are
      // told, and the gate is set once the chest is gone.
      await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: /)
      await waitGate(ctx, admin, AIR)
      const warned = ctx.waitForMessages(admin, new RegExp(`^The gate of cell ${c1.key} is left as it is: the block at 22, ${FLOOR}, ${GATE.z} in its plane holds something`))
      await ctx.command(admin, `/setblock 22 ${FLOOR} ${GATE.z} minecraft:chest`, /^Changed the block/)
      await ctx.command(admin, `/item replace block 22 ${FLOOR} ${GATE.z} container.0 with minecraft:diamond`, /^Replaced /)
      await warned
      await ctx.command(admin, '/season decorate close', /^The decorating window for Season 2 is closed\.$/)
      await ctx.sync(admin, [], 40)
      assert(await ctx.holds(admin, `block 22 ${FLOOR} ${GATE.z} minecraft:chest{Items:[{id:"minecraft:diamond"}]}`), 'the chest keeps its diamond')
      assert(await ctx.holds(admin, `block 21 ${FLOOR} ${GATE.z} ${AIR}`), 'and the rest of the gate waits')
      assert(audit(ctx).some((e) => e.action === 'gate' && e.target === c1.key && /^contents at /.test(e.details)), 'it is audited')
      await ctx.command(admin, `/setblock 22 ${FLOOR} ${GATE.z} ${AIR}`, /^Changed the block/)
      await waitGate(ctx, admin, BARS)
    },
    async (ctx) => {
      const admin = await ctx.admin()
      await waitGate(ctx, admin, BARS)
      await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: /)
      await waitGate(ctx, admin, AIR)
    }
  ]
}
