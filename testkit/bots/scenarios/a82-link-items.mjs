// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A82. /hof link item gives named signs; placing one far from the Hall makes a glowing sign with
// the configured lines and a trigger of the link, and a click on it takes a player to the Hall
// spawn; a survival player can't break it; a builder breaking it takes the trigger off and gets the
// sign back; a player without seasons.link.place can't place one (M8 plan, Link items).
import { assert } from '../lib/context.mjs'
import { FLOOR, breakBlock } from '../lib/hall.mjs'
import { HALL_SPAWN } from '../lib/hub.mjs'
import { WELCOME, link, linkInFile } from '../lib/links.mjs'
import { hallToVisit, middle, standAt, waitNear } from '../lib/visits.mjs'
import { click } from '../lib/wand.mjs'
import { forceload, permit } from '../lib/zones.mjs'

const SIGN = { x: 2000, y: FLOOR, z: 0 }
const OTHER = { x: 2002, y: FLOOR, z: 0 }

/** Holds a link sign in the main hand, as the bot would by choosing its hotbar slot. */
async function holdSign (ctx, admin, bot) {
  await ctx.eventually(async () => {
    const sign = bot.inventory.items().find((item) => item.name === 'oak_sign')
    assert(sign, `${bot.username} has a link sign`)
    assert(sign.slot >= 36, `the sign is on the hotbar: slot ${sign.slot}`)
    bot.setQuickBarSlot(sign.slot - 36)
  })
  await ctx.sync(admin, [bot], 2)
}

function signs (bot) {
  return bot.inventory.items().filter((item) => item.name === 'oak_sign').reduce((n, item) => n + item.count, 0)
}

export default {
  name: 'a82-link-items',
  async run (ctx) {
    const admin = await ctx.admin()
    const visitor = await ctx.join('Visitor')
    await hallToVisit(ctx, admin, { sealed: null })
    await forceload(ctx, admin, SIGN, 8)
    await link(ctx, admin, 'add hall-sign to hall', /^Link hall-sign leads to the Hall spawn, /)
    await ctx.command(admin, '/clear', /Removed|No items/)
    await link(ctx, admin, 'item hall-sign 3', /^Gave 3 link signs for hall-sign to SeasonsAdmin\.$/)
    await ctx.eventually(async () => assert(signs(admin) === 3, `three signs: ${signs(admin)}`))

    // Placing one makes a glowing sign with the configured lines, and a trigger of the link.
    await standAt(ctx, admin, ctx.adminName, { x: SIGN.x + 1, y: FLOOR, z: SIGN.z })
    await holdSign(ctx, admin, admin)
    await ctx.command(admin, `/scenario place ${ctx.adminName} ${SIGN.x} ${SIGN.y} ${SIGN.z} minecraft:oak_sign held`, /^Placed /)
    await ctx.eventually(async () => {
      const data = await ctx.command(admin, `/data get block ${SIGN.x} ${SIGN.y} ${SIGN.z} front_text`, /front_text|has the following block data/)
      assert(/Hall of Fame/.test(data) && /has_glowing_text: 1b/.test(data), `the sign reads and glows: ${data}`)
    })
    assert(linkInFile(ctx, 'hall-sign').triggers.some((t) => t.use && t.use.pos[0] === SIGN.x), 'the sign is a trigger of hall-sign')

    // A click on it takes a player to the Hall.
    await standAt(ctx, admin, 'Visitor', { x: SIGN.x - 1, y: FLOOR, z: SIGN.z })
    const welcomed = ctx.waitForMessages(visitor, WELCOME)
    await click(ctx, admin, 'Visitor', SIGN)
    await welcomed
    await waitNear(ctx, admin, 'Visitor', middle(HALL_SPAWN))

    // A survival player can't break it; a builder can, getting the sign back.
    await standAt(ctx, admin, 'Visitor', { x: SIGN.x - 1, y: FLOOR, z: SIGN.z })
    assert(!await breakBlock(ctx, admin, 'Visitor', SIGN), 'a visitor can\'t break the link sign')
    await permit(ctx, admin, ctx.adminName, 'seasons.hof.bypass')
    const before = signs(admin)
    assert(await breakBlock(ctx, admin, ctx.adminName, SIGN), 'a builder breaks it')
    await ctx.eventually(async () => assert(signs(admin) === before + 1, `the sign came back: ${signs(admin)}`))
    await ctx.eventually(async () => assert(!linkInFile(ctx, 'hall-sign').triggers?.length, 'the trigger is off the link'))

    // Without seasons.link.place, a player can't place one.
    await link(ctx, admin, 'item hall-sign 1 Visitor', /^Gave 1 link sign for hall-sign to Visitor\.$/)
    await standAt(ctx, admin, 'Visitor', { x: OTHER.x + 1, y: FLOOR, z: OTHER.z })
    await holdSign(ctx, admin, visitor)
    const refused = ctx.waitForMessages(visitor, /^You can't place link signs\.$/)
    await ctx.command(admin, `/scenario place Visitor ${OTHER.x} ${OTHER.y} ${OTHER.z} minecraft:oak_sign held`, /^Placing .* was refused$/)
    await refused
  }
}
