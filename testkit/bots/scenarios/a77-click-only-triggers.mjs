// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A77. A sign, a button, a door and a lever made links are taken only by a right-click: a player
// standing in each, on the block above, and walking into it goes nowhere, and the button's click is
// taken over. A pressure plate link fires on stepping, once until stepped off; a one-block trigger
// area fires on walking into it; a both floor tile fires on a click and on standing (M8 plan,
// Trigger kinds).
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { FOLLOWED, link, setBlock } from '../lib/links.mjs'
import { hallToVisit, middle, move, near, standAt, waitNear } from '../lib/visits.mjs'
import { click } from '../lib/wand.mjs'

const SPOT = { x: 150, y: FLOOR, z: 160 }
const SIGN = { x: 10, y: FLOOR, z: 21 }
const BUTTON = { x: 10, y: FLOOR, z: 23 }
const DOOR = { x: 10, y: FLOOR, z: 25 }
const LEVER = { x: 10, y: FLOOR, z: 27 }
const PLATE = { x: 14, y: FLOOR, z: 21 }
const CUBE = { x: 14, y: FLOOR, z: 24 }
const TILE = { x: 14, y: FLOOR - 1, z: 27 }

/** Resolves if the player is still where they stood after two sweeps; rejects if moved. */
async function stays (ctx, admin, visitor, at) {
  await ctx.sync(admin, [visitor], 30)
  const now = await ctx.position(admin, 'Visitor')
  assert(near(now, middle(at), 0.7), `the visitor stayed at ${JSON.stringify(at)}, now ${JSON.stringify(now)}`)
}

export default {
  name: 'a77-click-only-triggers',
  async run (ctx) {
    const admin = await ctx.admin()
    const visitor = await ctx.join('Visitor')
    await hallToVisit(ctx, admin, { sealed: null })
    await ctx.command(admin, '/forceload add 140 150 160 170', /^(Marked|No chunks were marked)/)
    await ctx.command(admin, '/season config set links.access.other everyone', /^Set links\.access\.other to everyone/)

    await setBlock(ctx, admin, SIGN, 'minecraft:oak_sign')
    await setBlock(ctx, admin, BUTTON, 'minecraft:stone_button[face=floor]')
    await setBlock(ctx, admin, DOOR, 'minecraft:oak_door[half=lower]')
    await setBlock(ctx, admin, { x: DOOR.x, y: DOOR.y + 1, z: DOOR.z }, 'minecraft:oak_door[half=upper]')
    await setBlock(ctx, admin, LEVER, 'minecraft:lever[face=floor]')
    await setBlock(ctx, admin, PLATE, 'minecraft:stone_pressure_plate')
    await setBlock(ctx, admin, TILE, 'minecraft:polished_andesite')
    for (const [id, at] of [['sign', SIGN], ['button', BUTTON], ['door', DOOR], ['lever', LEVER]]) {
      await link(ctx, admin, `add ${id} from ${at.x} ${at.y} ${at.z} to ${SPOT.x} ${SPOT.y} ${SPOT.z}`, new RegExp(`^Link ${id} leads to 150, -60, 160, from the block at ${at.x}, ${at.y}, ${at.z}, clicked\\.$`))
    }
    await link(ctx, admin, `add plate from ${PLATE.x} ${PLATE.y} ${PLATE.z} to ${SPOT.x} ${SPOT.y} ${SPOT.z}`, /, stepped on\.$/)
    await link(ctx, admin, `add cube from ${CUBE.x} ${CUBE.y} ${CUBE.z} --trigger area to ${SPOT.x} ${SPOT.y} ${SPOT.z}`, /, from an area from 14, -60, 24 to 14, -60, 24\.$/)
    await link(ctx, admin, `add tile from ${TILE.x} ${TILE.y} ${TILE.z} --trigger both to ${SPOT.x} ${SPOT.y} ${SPOT.z}`, /, clicked or stepped on\.$/)

    // Standing in each clickable link's block, on the block above it, and walking into it: nothing.
    for (const at of [SIGN, BUTTON, DOOR, LEVER]) {
      await standAt(ctx, admin, 'Visitor', at)
      await stays(ctx, admin, visitor, at)
      const above = { x: at.x, y: at.y + 1, z: at.z }
      await standAt(ctx, admin, 'Visitor', above)
      await stays(ctx, admin, visitor, above)
      const beside = { x: at.x + 1, y: at.y, z: at.z }
      await standAt(ctx, admin, 'Visitor', beside)
      await move(ctx, admin, 'Visitor', middle(at), 'walk')
      await stays(ctx, admin, visitor, at)
    }

    // A click takes each, and the button's click is Seasons'.
    for (const at of [SIGN, BUTTON, DOOR, LEVER]) {
      await standAt(ctx, admin, 'Visitor', { x: 12, y: FLOOR, z: at.z })
      const trip = ctx.waitForMessages(visitor, FOLLOWED)
      const line = await click(ctx, admin, 'Visitor', at)
      assert(/\(the click was taken over\)$/.test(line), `the click is taken over: ${line}`)
      await trip
      await waitNear(ctx, admin, 'Visitor', middle(SPOT))
      await ctx.advance(admin, 6_000)
    }

    // The plate fires underfoot, once until stepped off.
    await standAt(ctx, admin, 'Visitor', { x: 16, y: FLOOR, z: 21 })
    let trip = ctx.waitForMessages(visitor, FOLLOWED)
    await standAt(ctx, admin, 'Visitor', PLATE)
    await trip
    await waitNear(ctx, admin, 'Visitor', middle(SPOT))
    await ctx.advance(admin, 6_000)

    // A one-block trigger area fires on walking into it.
    await standAt(ctx, admin, 'Visitor', { x: 16, y: FLOOR, z: 24 })
    trip = ctx.waitForMessages(visitor, FOLLOWED)
    await move(ctx, admin, 'Visitor', middle(CUBE), 'walk')
    await trip
    await waitNear(ctx, admin, 'Visitor', middle(SPOT))
    await ctx.advance(admin, 6_000)

    // A both tile fires on standing on it, and on a click.
    await standAt(ctx, admin, 'Visitor', { x: 16, y: FLOOR, z: 27 })
    trip = ctx.waitForMessages(visitor, FOLLOWED)
    await standAt(ctx, admin, 'Visitor', { x: TILE.x, y: FLOOR, z: TILE.z })
    await trip
    await waitNear(ctx, admin, 'Visitor', middle(SPOT))
    await ctx.advance(admin, 6_000)
    await standAt(ctx, admin, 'Visitor', { x: 16, y: FLOOR, z: 27 })
    trip = ctx.waitForMessages(visitor, FOLLOWED)
    await click(ctx, admin, 'Visitor', TILE)
    await trip
  }
}
