// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A30's portal route, through a real nether portal: no stand-in, so the game itself decides the
// trip. A portal in the Nether whose spot in the overworld (eight times as far out) lies just
// outside the base, while an old portal inside the base is the nearest one there, which the game
// would take the player to. The trip is refused (the teleport to the old portal, or else the
// arrival), Pat stays on the Nether side and is told why. The audit entry names the old portal's
// spot, so the game really was taking Pat there. (A second trip can't follow: a bot that sends no
// moves isn't ticked, so its portal cooldown never runs out. A bypass holder is A30's walk in.)
import { SECOND, assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { BASE, LOCKED, drawBase, launchFrontier } from '../lib/zones.mjs'

const NETHER = 'minecraft:the_nether'
// In the Nether at 68, 62: the overworld spot is 544, 496, 44 blocks from the base's centre, past
// its radius of 30. The old portal inside the base stands at 520, 500, 24 blocks from that spot.
const NETHER_PORTAL = { x: 68, y: 70, z: 62 }
const OLD_PORTAL = { x: BASE.x + 20, y: FLOOR, z: BASE.z }

export default {
  name: 'a30-real-portal',
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    await launchFrontier(ctx, admin)
    await drawBase(ctx, admin)
    const inNether = (command) => ctx.command(admin, `/execute in ${NETHER} run ${command}`, /^(Successfully filled|No blocks were filled|Marked|No chunks were marked|Changed the block)/)
    const dimension = async () => JSON.parse(await ctx.entityData(admin, 'Pat', 'Dimension'))

    // The old portal inside the base.
    const o = OLD_PORTAL
    await ctx.command(admin, `/fill ${o.x - 1} ${o.y - 1} ${o.z} ${o.x + 2} ${o.y + 3} ${o.z} minecraft:obsidian`, /^Successfully filled /)
    await ctx.command(admin, `/fill ${o.x} ${o.y} ${o.z} ${o.x + 1} ${o.y + 2} ${o.z} minecraft:nether_portal[axis=x]`, /^Successfully filled /)

    // The new one in the Nether, in a room of its own.
    const n = NETHER_PORTAL
    await inNether(`forceload add ${n.x - 16} ${n.z - 16} ${n.x + 16} ${n.z + 16}`)
    await inNether(`fill ${n.x - 4} ${n.y - 2} ${n.z - 3} ${n.x + 5} ${n.y + 5} ${n.z + 3} minecraft:air`)
    await inNether(`fill ${n.x - 4} ${n.y - 2} ${n.z - 3} ${n.x + 5} ${n.y - 2} ${n.z + 3} minecraft:netherrack`)
    await inNether(`fill ${n.x - 1} ${n.y - 1} ${n.z} ${n.x + 2} ${n.y + 3} ${n.z} minecraft:obsidian`)
    await inNether(`fill ${n.x} ${n.y} ${n.z} ${n.x + 1} ${n.y + 2} ${n.z} minecraft:nether_portal[axis=x]`)
    await inNether(`fill ${n.x - 4} ${n.y - 1} ${n.z - 3} ${n.x + 5} ${n.y - 1} ${n.z - 1} minecraft:netherrack`)

    // Pat stands by the Nether portal, then steps in.
    await ctx.command(admin, `/execute in ${NETHER} run tp Pat ${n.x + 0.5} ${n.y} ${n.z - 2 + 0.5}`, /^Teleported /)
    await ctx.eventually(async () => assert(await dimension() === NETHER, 'Pat is in the Nether'))
    await ctx.sync(admin, [pat], 20)
    const told = ctx.waitForMessages(pat, LOCKED('base'), 30 * SECOND, 'the refusal at the old portal')
    await ctx.command(admin, `/execute in ${NETHER} run tp Pat ${n.x + 0.5} ${n.y} ${n.z + 0.5}`, /^Teleported /)
    await told
    await ctx.eventually(async () => assert(await dimension() === NETHER, 'Pat is back in the Nether'), 5 * SECOND)
    const at = await ctx.position(admin, 'Pat')
    assert(Math.abs(at.x - (n.x + 0.5)) < 4 && Math.abs(at.z - (n.z + 0.5)) < 4, `Pat is by the Nether portal, at ${JSON.stringify(at)}`)
    // Out of the portal again.
    await ctx.command(admin, `/execute in ${NETHER} run tp Pat ${n.x + 0.5} ${n.y} ${n.z - 2 + 0.5}`, /^Teleported /)
    const audit = ctx.readFile(`world/dimensions/minecraft/overworld/seasons/audit/${new Date().toISOString().slice(0, 7)}.jsonl`)
    assert(/"action":"zone-blocked","target":"base",[^\n]*(teleport in by portal|arrive by portal) at 52\d, /.test(audit), `the refused trip is audited: ${audit}`)
  }
}
