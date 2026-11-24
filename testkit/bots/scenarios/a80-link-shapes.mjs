// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A80. A sign, a button and a doorway area on one link all reach the same spot; --two-way makes a
// link and its -back partner, the partner asks for a trigger, each lands at the other's landing,
// and arriving never fires the trigger landed in; removing one refuses without --both, and unpair
// keeps both (M8 plan, Shapes the owner asked for).
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { FOLLOWED, WELCOME, link, linkInFile, sameBlock, setBlock } from '../lib/links.mjs'
import { hallToVisit, middle, move, standAt, waitNear } from '../lib/visits.mjs'
import { click } from '../lib/wand.mjs'

const SPOT = { x: 150, y: FLOOR, z: 160 }
const SIGN = { x: 10, y: FLOOR, z: 22 }
const BUTTON = { x: 10, y: FLOOR, z: 24 }
const FAR = { x: 200, y: FLOOR, z: 200 }
const FAR_BUTTON = { x: 203, y: FLOOR, z: 200 }

export default {
  name: 'a80-link-shapes',
  async run (ctx) {
    const admin = await ctx.admin()
    const visitor = await ctx.join('Visitor')
    await hallToVisit(ctx, admin, { sealed: null })
    await ctx.command(admin, '/forceload add 140 150 210 210', /^(Marked|No chunks were marked)/)
    await ctx.command(admin, '/season config set links.access.other everyone', /^Set links\.access\.other to everyone/)

    // Many to one: a sign, a button and a doorway, all to SPOT.
    await setBlock(ctx, admin, SIGN, 'minecraft:oak_sign')
    await setBlock(ctx, admin, BUTTON, 'minecraft:stone_button[face=floor]')
    await link(ctx, admin, `add many from ${SIGN.x} ${SIGN.y} ${SIGN.z} to ${SPOT.x} ${SPOT.y} ${SPOT.z}`, /^Link many leads to 150, -60, 160, from the block at 10, -60, 22, clicked\.$/)
    await link(ctx, admin, `from many ${BUTTON.x} ${BUTTON.y} ${BUTTON.z}`, /^Link many gains a trigger: the block at 10, -60, 24, clicked\.$/)
    await link(ctx, admin, `from many 9 ${FLOOR} 27 9 ${FLOOR + 2} 29`, /^Link many gains a trigger: an area from 9, -60, 27 to 9, -58, 29\.$/)
    await link(ctx, admin, 'trigger list many', [/^1\. the block at 10, -60, 22, clicked$/, /^2\. the block at 10, -60, 24, clicked$/, /^3\. an area from 9, -60, 27 to 9, -58, 29$/])
    assert(linkInFile(ctx, 'many').triggers.length === 3, 'three triggers in links.yml')

    await standAt(ctx, admin, 'Visitor', { x: 12, y: FLOOR, z: 23 })
    await click(ctx, admin, 'Visitor', SIGN)
    await waitNear(ctx, admin, 'Visitor', middle(SPOT))
    await ctx.advance(admin, 6_000)
    await standAt(ctx, admin, 'Visitor', { x: 12, y: FLOOR, z: 23 })
    await click(ctx, admin, 'Visitor', BUTTON)
    await waitNear(ctx, admin, 'Visitor', middle(SPOT))
    await ctx.advance(admin, 6_000)
    await standAt(ctx, admin, 'Visitor', { x: 11, y: FLOOR, z: 28 })
    const followed = ctx.waitForMessages(visitor, FOLLOWED)
    await move(ctx, admin, 'Visitor', { x: 8.5, y: FLOOR, z: 28.5 }, 'walk')
    await followed
    await waitNear(ctx, admin, 'Visitor', middle(SPOT))
    await ctx.advance(admin, 6_000)

    // Two-way: the hub's button and one far out, each landing at the other's.
    const HUB_BUTTON = { x: 14, y: FLOOR, z: 29 }
    await setBlock(ctx, admin, HUB_BUTTON, 'minecraft:stone_button[face=floor]')
    await link(ctx, admin, `add far from ${HUB_BUTTON.x} ${HUB_BUTTON.y} ${HUB_BUTTON.z} to ${FAR.x} ${FAR.y} ${FAR.z} --two-way`, [
      /^Link far leads to 200, -60, 200, from the block at 14, -60, 29, clicked\.$/,
      /^Link far-back leads back to far\. Give it a trigger at the other end: wand-click a block there, or \/hof link trigger add far-back here\.$/
    ])
    await setBlock(ctx, admin, FAR_BUTTON, 'minecraft:stone_button[face=floor]')
    await link(ctx, admin, `trigger add far-back ${FAR_BUTTON.x} ${FAR_BUTTON.y} ${FAR_BUTTON.z}`, /^Link far-back gains a trigger: the block at 203, -60, 200, clicked\.$/)
    assert(linkInFile(ctx, 'far').pair === 'far-back' && linkInFile(ctx, 'far-back').pair === 'far', 'a pair in links.yml')
    await link(ctx, admin, `landing far-back ${FAR.x} ${FAR.y} ${FAR.z}`, /^Link far-back lands at 200, -60, 200\.$/)

    await standAt(ctx, admin, 'Visitor', { x: 13, y: FLOOR, z: 29 })
    await click(ctx, admin, 'Visitor', HUB_BUTTON)
    await waitNear(ctx, admin, 'Visitor', middle(FAR))
    await ctx.advance(admin, 6_000)
    // far-back leads to far's landing: in front of the hub's button, not on it.
    const welcomed = ctx.waitForMessages(visitor, WELCOME)
    await standAt(ctx, admin, 'Visitor', { x: 202, y: FLOOR, z: 200 })
    await click(ctx, admin, 'Visitor', FAR_BUTTON)
    await welcomed
    const back = await ctx.eventually(async () => {
      const at = await ctx.position(admin, 'Visitor')
      assert(Math.hypot(at.x - (HUB_BUTTON.x + 0.5), at.z - (HUB_BUTTON.z + 0.5)) <= 3, `beside the hub's button: ${JSON.stringify(at)}`)
      return at
    })
    assert(!sameBlock(back, HUB_BUTTON), 'not on the button')

    // Removing one of a pair.
    await link(ctx, admin, 'remove far', /^Link far is paired with far-back: add --both to remove both, or \/hof link unpair far first\.$/)
    await link(ctx, admin, 'unpair far', /^Link far is no longer paired\.$/)
    assert(linkInFile(ctx, 'far') && linkInFile(ctx, 'far-back'), 'unpair keeps both')
    await link(ctx, admin, 'pair far far-back', /^Links far and far-back are now a two-way pair\.$/)
    await link(ctx, admin, 'remove far --both', /^Removed links far and far-back\.$/)
    assert(!linkInFile(ctx, 'far') && !linkInFile(ctx, 'far-back'), 'both gone')
  }
}
