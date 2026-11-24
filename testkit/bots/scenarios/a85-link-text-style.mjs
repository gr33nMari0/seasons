// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A85. By command an admin sets a sign trigger's line gold and bold, a line mixing colours with
// MiniMessage, and glowing on, without ink; a click tag is stripped; a holder of only
// seasons.admin.links without seasons.link.text.style is refused; a hand-written link sign nobody
// edits keeps its text (M8 plan, Styling text).
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { link, setBlock } from '../lib/links.mjs'
import { hallToVisit } from '../lib/visits.mjs'
import { permit } from '../lib/zones.mjs'

const SIGN = { x: 10, y: FLOOR, z: 22 }
const HAND = { x: 10, y: FLOOR, z: 24 }

async function front (ctx, admin, at) {
  return ctx.command(admin, `/data get block ${at.x} ${at.y} ${at.z} front_text`, /front_text|has the following block data/)
}

export default {
  name: 'a85-link-text-style',
  async run (ctx) {
    const admin = await ctx.admin()
    const visitor = await ctx.join('Visitor')
    await hallToVisit(ctx, admin, { sealed: null })
    await setBlock(ctx, admin, SIGN, 'minecraft:oak_sign')
    await setBlock(ctx, admin, HAND, 'minecraft:oak_sign{front_text:{messages:["by hand","","",""]}}')
    await link(ctx, admin, `add styled from ${SIGN.x} ${SIGN.y} ${SIGN.z} to hall`, /^Link styled leads to /)
    await link(ctx, admin, `add plain from ${HAND.x} ${HAND.y} ${HAND.z} to hall`, /^Link plain leads to /)

    await link(ctx, admin, 'sign styled line 2 <gold><bold>Hall of Fame', /^Link styled's sign line 2 now reads Hall of Fame\.$/)
    await link(ctx, admin, 'sign styled line 3 <aqua>Season <red>3 <click:run_command:\'/op Visitor\'>now</click>', /^Link styled's sign line 3 now reads Season 3 now\.$/)
    await link(ctx, admin, 'sign styled glow on', /^Link styled's sign glows now\.$/)
    const data = await front(ctx, admin, SIGN)
    assert(/Hall of Fame/.test(data) && /bold: 1b/.test(data) && /"gold"/.test(data), `gold and bold: ${data}`)
    assert(/"aqua"/.test(data) && /"red"/.test(data), `mixed colours: ${data}`)
    assert(!/run_command|click_event|\/op/.test(data), `no click: ${data}`)
    assert(/has_glowing_text: 1b/.test(data), `glowing: ${data}`)
    await link(ctx, admin, 'sign styled glow off', /^Link styled's sign doesn't glow now\.$/)

    // Nobody's edit leaves a hand-written sign as it was.
    await ctx.command(admin, '/season reload', /^Reloaded /)
    assert(/by hand/.test(await front(ctx, admin, HAND)), 'the hand-written sign keeps its text')

    // Without seasons.link.text.style.
    await permit(ctx, admin, 'Visitor', 'seasons.admin.links')
    await ctx.command(visitor, '/hof link sign styled line 1 <red>Mine', /^You may not style link text\.$/)
  }
}
