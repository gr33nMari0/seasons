// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// A86. /hof link text add shows a floating text a player right-clicks to take the link; walking
// through it does nothing until --walk is set; left-clicking does nothing until --left is set;
// scale resizes it and its hitbox; its lines can be styled; after a restart and /hof reconcile
// exactly one text and one hitbox remain (M8 plan, Floating text).
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { FOLLOWED, link, linkInFile } from '../lib/links.mjs'
import { hallToVisit, middle, move, standAt, waitNear } from '../lib/visits.mjs'

const SPOT = { x: 150, y: FLOOR, z: 160 }
const TEXT = { x: 14, y: FLOOR + 1, z: 25 }
const TEXTS = '@e[type=minecraft:text_display,x=13,y=-60,z=24,dx=2,dy=3,dz=2]'
const HITBOXES = '@e[type=minecraft:interaction,x=13,y=-60,z=24,dx=2,dy=3,dz=2]'

async function count (ctx, admin, selector) {
  const line = await ctx.command(admin, `/execute if entity ${selector}`, /^Test (passed|failed)/)
  if (line.startsWith('Test failed')) return 0
  return Number(/[Cc]ount: (\d+)/.exec(line)?.[1] ?? 1)
}

/** The interaction entity the bot sees nearest a spot. */
function hitbox (bot) {
  return Object.values(bot.entities).find((e) => e.name === 'interaction' && Math.abs(e.position.x - (TEXT.x + 0.5)) < 1 && Math.abs(e.position.z - (TEXT.z + 0.5)) < 1)
}

export default {
  name: 'a86-floating-text-links',
  sessions: [
    async (ctx) => {
      const admin = await ctx.admin()
      const visitor = await ctx.join('Visitor')
      await hallToVisit(ctx, admin, { sealed: null })
      await ctx.command(admin, '/forceload add 140 150 160 170', /^(Marked|No chunks were marked)/)
      await ctx.command(admin, '/season config set links.access.other everyone', /^Set links\.access\.other to everyone/)
      await link(ctx, admin, `add floating to ${SPOT.x} ${SPOT.y} ${SPOT.z}`, /^Link floating leads to /)
      await link(ctx, admin, `text add floating ${TEXT.x} ${TEXT.y} ${TEXT.z} <gold>Season 3`, /^Link floating gains a trigger: floating text at 14, -59, 25\.$/)
      await ctx.eventually(async () => assert(await count(ctx, admin, TEXTS) === 1 && await count(ctx, admin, HITBOXES) === 1, 'one text and one hitbox'))

      // A right-click takes it.
      await standAt(ctx, admin, 'Visitor', { x: TEXT.x + 2, y: FLOOR, z: TEXT.z })
      await ctx.eventually(async () => assert(hitbox(visitor), 'the bot sees the hitbox'))
      let trip = ctx.waitForMessages(visitor, FOLLOWED)
      await visitor.activateEntity(hitbox(visitor))
      await trip
      await waitNear(ctx, admin, 'Visitor', middle(SPOT))
      await ctx.advance(admin, 6_000)

      // Walking through it does nothing, until --walk; a left click nothing, until --left.
      await standAt(ctx, admin, 'Visitor', { x: TEXT.x + 2, y: FLOOR, z: TEXT.z })
      await move(ctx, admin, 'Visitor', middle({ x: TEXT.x, y: FLOOR, z: TEXT.z }), 'walk')
      await ctx.sync(admin, [visitor], 30)
      assert(Math.abs((await ctx.position(admin, 'Visitor')).x - (TEXT.x + 0.5)) < 1, 'walking through did nothing')
      await standAt(ctx, admin, 'Visitor', { x: TEXT.x + 2, y: FLOOR, z: TEXT.z })
      await ctx.eventually(async () => assert(hitbox(visitor), 'the bot sees the hitbox'))
      visitor.attack(hitbox(visitor))
      await ctx.sync(admin, [visitor], 20)
      assert(Math.abs((await ctx.position(admin, 'Visitor')).x - (TEXT.x + 2.5)) < 1, 'a left click did nothing')
      await link(ctx, admin, 'text edit floating 1 --walk 1 --left', /^Link floating's floating text 1 is now taken by a click, a left click or walking within 1\.$/)
      trip = ctx.waitForMessages(visitor, FOLLOWED)
      await move(ctx, admin, 'Visitor', middle({ x: TEXT.x, y: FLOOR, z: TEXT.z }), 'walk')
      await trip
      await ctx.advance(admin, 6_000)

      // Scale and style.
      await link(ctx, admin, 'text scale floating 1 2', /^Link floating's floating text 1 is now 2 times its size\.$/)
      await link(ctx, admin, 'text line floating 1 1 <aqua><bold>The third season', /^Link floating's floating text 1 line 1 now reads The third season\.$/)
      assert(linkInFile(ctx, 'floating').triggers[0].text.scale === 2, 'the scale is kept')
      await ctx.eventually(async () => {
        const data = await ctx.command(admin, `/data get entity ${TEXTS.replace(']', ',limit=1]')} text`, /has the following entity data|text/)
        assert(/third season/.test(data) && /"aqua"/.test(data), `the text is styled: ${data}`)
      })
    },
    async (ctx) => {
      const admin = await ctx.admin()
      await ctx.eventually(async () => assert(await count(ctx, admin, TEXTS) === 1 && await count(ctx, admin, HITBOXES) === 1, 'one text and one hitbox after a restart'))
      await ctx.command(admin, '/hof reconcile', /./)
      await ctx.sync(admin, [], 20)
      assert(await count(ctx, admin, TEXTS) === 1 && await count(ctx, admin, HITBOXES) === 1, 'still one of each after reconciling')
    }
  ]
}
