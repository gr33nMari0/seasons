// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A79. Links from x y z, ~, here, sel and point to a spot, here, a point, a link, season 3, the
// Hall, a gallery and return each land where they should, facing the yaw set; a link leading to a
// link lands at its landing, not on its trigger (M8 plan, The model and Commands).
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { HALL_SPAWN } from '../lib/hub.mjs'
import { FOLLOWED, WELCOME, go, link, linkInFile, sameBlock, setBlock } from '../lib/links.mjs'
import { HOME, hallToVisit, middle, move, standAt, waitNear } from '../lib/visits.mjs'
import { click } from '../lib/wand.mjs'

const S3 = { x: 300, y: FLOOR, z: 300 }
const STEPS = { x: 150, y: FLOOR, z: 150 }
const SPOT = { x: 150, y: FLOOR, z: 160 }
const LODESTONE = { x: 10, y: FLOOR, z: 22 }
const ADMIN = { x: 12, y: FLOOR, z: 28 }
const GALLERY_ENTRY = { x: 21, y: FLOOR, z: 21 }

export default {
  name: 'a79-link-combinations',
  async run (ctx) {
    const admin = await ctx.admin()
    const visitor = await ctx.join('Visitor')
    await hallToVisit(ctx, admin, { sealed: null })
    await ctx.command(admin, '/forceload add 140 140 160 170', /^(Marked|No chunks were marked)/)
    await ctx.command(admin, '/forceload add 290 290 310 310', /^(Marked|No chunks were marked)/)
    await ctx.command(admin, '/season config set links.access.other everyone', /^Set links\.access\.other to everyone in config\.yml/)
    await ctx.command(admin, `/season spawn set at ${S3.x} ${S3.y} ${S3.z}`, /^Season 3's spawn is now /)
    await ctx.command(admin, `/hof point add steps ${STEPS.x} ${STEPS.y} ${STEPS.z} 90`, /^Point steps is at 150, -60, 150\.$/)

    // Sources: a typed block, a relative block, where the admin stands, the selection, a point.
    await setBlock(ctx, admin, LODESTONE, 'minecraft:lodestone')
    await link(ctx, admin, `add l-xyz from ${LODESTONE.x} ${LODESTONE.y} ${LODESTONE.z} to ${SPOT.x} ${SPOT.y} ${SPOT.z} 90`,
      /^Link l-xyz leads to 150, -60, 160, from the block at 10, -60, 22, clicked\.$/)
    await standAt(ctx, admin, ctx.adminName, ADMIN)
    await setBlock(ctx, admin, { x: ADMIN.x, y: FLOOR, z: ADMIN.z + 2 }, 'minecraft:lodestone')
    await link(ctx, admin, 'add l-rel from ~ ~ ~2 to point steps', /^Link l-rel leads to point steps, from the block at 12, -60, 30, clicked\.$/)
    await link(ctx, admin, 'add l-here from here --trigger step to hall', /^Link l-here leads to the Hall spawn, from the block at 12, -60, 28, stepped on\.$/)
    await ctx.command(admin, '/hof sel pos1 9 -60 24', /^Corner 1: /)
    await ctx.command(admin, '/hof sel pos2 9 -58 26', /^Selected 9, -60, 24 to 9, -58, 26 /)
    await link(ctx, admin, 'add l-sel from sel to gallery season-2', /^Link l-sel leads to gallery season-2's entry, from an area from 9, -60, 24 to 9, -58, 26\.$/)
    await link(ctx, admin, 'add l-point from point steps to link l-xyz', /^Link l-point leads to link l-xyz, from the block at 150, -60, 150, stepped on\.$/)
    await link(ctx, admin, 'add l-s3 to season 3', /^Link l-s3 leads to Season 3's spawn, from no trigger \(only \/hof link go and send\)\.$/)
    await link(ctx, admin, 'add l-back to return', /^Link l-back leads to back where they came from, from no trigger/)
    await standAt(ctx, admin, ctx.adminName, { x: 155, y: FLOOR, z: 155 })
    await link(ctx, admin, 'add l-spot to here', /^Link l-spot leads to 155, -60, 155, from no trigger/)
    await link(ctx, admin, 'add l-bad to link l-bad', /^A link can't lead to itself\.$/)
    await link(ctx, admin, 'add l-bad to point nowhere', /^There's no point nowhere\.$/)
    assert(linkInFile(ctx, 'l-xyz').to.pos[0] === SPOT.x, `l-xyz is in links.yml: ${JSON.stringify(linkInFile(ctx, 'l-xyz'))}`)
    assert(linkInFile(ctx, 'l-sel').triggers[0].area, 'l-sel is an area')

    // Each destination, by /hof link go.
    await standAt(ctx, admin, 'Visitor', HOME)
    await go(ctx, visitor, 'l-xyz', FOLLOWED)
    await waitNear(ctx, admin, 'Visitor', middle(SPOT))
    await go(ctx, visitor, 'l-rel', FOLLOWED)
    await waitNear(ctx, admin, 'Visitor', middle(STEPS))
    await go(ctx, visitor, 'l-s3', FOLLOWED)
    await waitNear(ctx, admin, 'Visitor', middle(S3))
    await go(ctx, visitor, 'l-spot', FOLLOWED)
    await waitNear(ctx, admin, 'Visitor', middle({ x: 155, y: FLOOR, z: 155 }))
    await go(ctx, visitor, 'l-sel', WELCOME)
    await waitNear(ctx, admin, 'Visitor', middle(GALLERY_ENTRY))
    // Back where they came from, from inside the Hall.
    await go(ctx, visitor, 'l-back', /^Back where you left\.$/)
    await waitNear(ctx, admin, 'Visitor', middle({ x: 155, y: FLOOR, z: 155 }))
    await go(ctx, visitor, 'l-here', WELCOME)
    await waitNear(ctx, admin, 'Visitor', middle(HALL_SPAWN))
    // To a link: its landing, beside its trigger, not on it.
    await go(ctx, visitor, 'l-point', WELCOME)
    const landed = await ctx.eventually(async () => {
      const at = await ctx.position(admin, 'Visitor')
      assert(Math.hypot(at.x - (LODESTONE.x + 0.5), at.z - (LODESTONE.z + 0.5)) <= 3, `beside l-xyz's trigger: ${JSON.stringify(at)}`)
      return at
    })
    assert(!sameBlock(landed, LODESTONE), 'not on the trigger')
    await go(ctx, visitor, 'l-back', /^Back where you left\.$/)

    // The triggers themselves: a click on the typed block, a walk through the selection.
    await standAt(ctx, admin, 'Visitor', { x: 12, y: FLOOR, z: 22 })
    const trip = ctx.waitForMessages(visitor, FOLLOWED)
    await click(ctx, admin, 'Visitor', LODESTONE)
    await trip
    await waitNear(ctx, admin, 'Visitor', middle(SPOT))
    await ctx.advance(admin, 6_000)
    await standAt(ctx, admin, 'Visitor', { x: 11, y: FLOOR, z: 25 })
    const welcomed = ctx.waitForMessages(visitor, WELCOME)
    await move(ctx, admin, 'Visitor', { x: 8.5, y: FLOOR, z: 25.5 }, 'walk')
    await welcomed
    await waitNear(ctx, admin, 'Visitor', middle(GALLERY_ENTRY))
  }
}
