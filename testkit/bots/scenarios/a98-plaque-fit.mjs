// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A98. A plaque on a wall block is drawn just in front of the face it faces, fixed rather than
// turning, and never inside the wall; in a nook too narrow for it, it shrinks to fit and /hof hall
// check says so; a plaque anchor on a block, set without a facing, gets one guessed from its open
// side once, kept in the gallery file. A plaque on the floor floats and turns as before
// (1.4.1 plan, H4).
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { hallToVisit } from '../lib/visits.mjs'

/** The text displays standing in one block. */
function texts (x, y, z, extra = '') {
  return `@e[type=minecraft:text_display,x=${x},y=${y},z=${z},dx=0,dy=0,dz=0${extra}]`
}

async function scaleOf (ctx, admin, selector) {
  const line = await ctx.command(admin, `/data get entity ${selector} transformation.scale`, /(has the following entity data: |^No entity was found|^Found no elements)/)
  const match = /\[([\d.]+)f/.exec(line)
  return match ? Number(match[1]) : null
}

export default {
  name: 'a98-plaque-fit',
  async run (ctx) {
    const admin = await ctx.admin()
    for (const name of ['Wall', 'Nook', 'Guess', 'Floor']) await ctx.join(name)
    // Cells c1 to c4 at x 20, 26, 32 and 38, z 22 to 30.
    await hallToVisit(ctx, admin, { cells: 4, sealed: null })
    // c1 to c3 get a back wall at z 29; c2's has pillars in front, three blocks apart.
    for (const x of [20, 26, 32]) {
      await ctx.command(admin, `/fill ${x} ${FLOOR} 29 ${x + 4} ${FLOOR + 4} 29 minecraft:stone`, /^Successfully filled/)
    }
    await ctx.command(admin, `/fill 26 ${FLOOR} 28 26 ${FLOOR + 4} 28 minecraft:stone`, /^Successfully filled/)
    await ctx.command(admin, `/fill 30 ${FLOOR} 28 30 ${FLOOR + 4} 28 minecraft:stone`, /^Successfully filled/)

    await ctx.command(admin, `/hof cell anchor season-2:c1 plaque 22 ${FLOOR + 2} 29`, /^Added a plaque anchor /)
    await ctx.command(admin, '/hof cell plaque season-2:c1 facing north', /^The plaque of season-2:c1 now faces north\.$/)
    await ctx.command(admin, `/hof cell anchor season-2:c2 plaque 28 ${FLOOR + 2} 29`, /^Added a plaque anchor /)
    await ctx.command(admin, '/hof cell plaque season-2:c2 facing north', /^The plaque of season-2:c2 now faces north\.$/)
    await ctx.command(admin, `/hof cell anchor season-2:c3 plaque 34 ${FLOOR + 2} 29`, /^Added a plaque anchor /)
    for (const [cell, name] of [['c1', 'Wall'], ['c2', 'Nook'], ['c3', 'Guess'], ['c4', 'Floor']]) {
      await ctx.command(admin, `/hof cell assign season-2:${cell} ${name}`, /^Assigned /)
    }
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)

    // On the wall: in front of its north face, fixed, at full size; nothing in the wall block.
    const wall = texts(22, FLOOR + 2, 28, ',nbt={billboard:"fixed"}')
    await ctx.eventually(async () => assert(await ctx.holds(admin, `entity ${wall}`), 'c1\'s plaque is fixed in front of the wall'))
    assert(!await ctx.holds(admin, `entity ${texts(22, FLOOR + 2, 29)}`), 'nothing is drawn inside the wall block')
    assert(await scaleOf(ctx, admin, wall) === 1, 'c1\'s plaque is at full size')

    // In the nook: smaller, to fit between the pillars, and the check says so.
    const nook = texts(28, FLOOR + 2, 28, ',nbt={billboard:"fixed"}')
    await ctx.eventually(async () => assert(await ctx.holds(admin, `entity ${nook}`), 'c2\'s plaque is in front of the wall'))
    const small = await scaleOf(ctx, admin, nook)
    assert(small !== null && small < 1 && small >= 0.25, `c2's plaque shrank to fit: ${small}`)
    await ctx.command(admin, '/hof hall check', /^Cell season-2:c2's plaque is shown at 0\.\d+ to fit in front of its block \(asked 1\.0\)\.$/)

    // Guessed: the wall's open side faces into the cell, north, and it is kept.
    await ctx.eventually(async () => assert(await ctx.holds(admin, `entity ${texts(34, FLOOR + 2, 28, ',nbt={billboard:"fixed"}')}`), 'c3\'s plaque is in front of the open face'))
    await ctx.command(admin, '/hof cell anchors season-2:c3', /^\d+\. plaque at 34, -58, 29, facing north$/)

    // On the floor, with room all round: floating and turning, as before.
    await ctx.eventually(async () => assert(await ctx.holds(admin, `entity @e[type=minecraft:text_display,x=39,y=${FLOOR},z=27,dx=0,dy=2,dz=0,nbt={billboard:"center"}]`), 'c4\'s plaque floats above its anchor'))
  }
}
