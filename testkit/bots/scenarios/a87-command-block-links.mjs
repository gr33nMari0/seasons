// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A87. A command block running hof link send @p behind redstone sends the nearest player;
// @a[distance=..5] sends the two within 5 and not the third; @e[type=pig] moves no pig and says so;
// into the Hall, --checks none sends a player who just took damage and the default refuses them; a
// player without the node can't run send (M8 plan, Command blocks).
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { HALL_SPAWN } from '../lib/hub.mjs'
import { link, setBlock } from '../lib/links.mjs'
import { hallToVisit, middle, near, standAt, waitNear } from '../lib/visits.mjs'

const S3 = { x: 300, y: FLOOR, z: 300 }
const BLOCK = { x: 100, y: FLOOR, z: 90 }
const POWER = { x: 100, y: FLOOR + 1, z: 90 }

/** Sets the command block's command and powers it once. */
async function fire (ctx, admin, command) {
  await setBlock(ctx, admin, POWER, 'minecraft:air')
  await setBlock(ctx, admin, BLOCK, `minecraft:command_block{Command:"${command}"}`)
  await setBlock(ctx, admin, POWER, 'minecraft:redstone_block')
  await ctx.sync(admin, [], 4)
}

async function lastOutput (ctx, admin) {
  return ctx.command(admin, `/data get block ${BLOCK.x} ${BLOCK.y} ${BLOCK.z} LastOutput`, /LastOutput|has the following block data/)
}

export default {
  name: 'a87-command-block-links',
  async run (ctx) {
    const admin = await ctx.admin()
    const visitor = await ctx.join('Visitor')
    const ann = await ctx.join('Ann')
    await ctx.join('Ben')
    await hallToVisit(ctx, admin, { sealed: null })
    await ctx.command(admin, '/forceload add 80 80 120 120', /^(Marked|No chunks were marked)/)
    await ctx.command(admin, '/forceload add 290 290 310 310', /^(Marked|No chunks were marked)/)
    await ctx.command(admin, `/season spawn set at ${S3.x} ${S3.y} ${S3.z}`, /^Season 3's spawn is now /)
    await link(ctx, admin, 'add hof-s3 to season 3', /^Link hof-s3 leads to /)
    await link(ctx, admin, 'add in to hall', /^Link in leads to the Hall spawn, /)
    await standAt(ctx, admin, ctx.adminName, HALL_SPAWN)

    const place = async () => {
      await standAt(ctx, admin, 'Visitor', { x: 100, y: FLOOR, z: 92 })
      await standAt(ctx, admin, 'Ann', { x: 100, y: FLOOR, z: 95 })
      await standAt(ctx, admin, 'Ben', { x: 100, y: FLOOR, z: 99 })
    }

    // @p: the nearest.
    await place()
    await fire(ctx, admin, 'hof link send @p hof-s3')
    await waitNear(ctx, admin, 'Visitor', middle(S3), 2)
    assert(near(await ctx.position(admin, 'Ann'), middle({ x: 100, y: FLOOR, z: 95 }), 0.6), 'Ann stays')

    // @a within 5: two of the three.
    await place()
    await fire(ctx, admin, 'hof link send @a[distance=..5.5] hof-s3')
    await waitNear(ctx, admin, 'Visitor', middle(S3), 2)
    await waitNear(ctx, admin, 'Ann', middle(S3), 2)
    assert(near(await ctx.position(admin, 'Ben'), middle({ x: 100, y: FLOOR, z: 99 }), 0.6), 'Ben stays')

    // A pig picked by @e stays, and the block says so.
    await ctx.command(admin, '/summon minecraft:pig 102 -60 90 {NoAI:1b,Tags:["a87"]}', /^Summoned new Pig/)
    await fire(ctx, admin, 'hof link send @e[type=minecraft:pig,tag=a87] hof-s3')
    assert(/other entities were left/.test(await lastOutput(ctx, admin)), 'the command block says the pig was left')
    await ctx.command(admin, '/kill @e[type=minecraft:pig,tag=a87]', /^Killed /)

    // Into the Hall: just hurt, refused by default, sent with --checks none.
    await place()
    await ctx.advance(admin, 6_000) // past the last trip's arrival immunity
    await ctx.command(admin, '/damage Visitor 1', /^Applied 1/)
    const hurt = ctx.waitForMessages(visitor, /^You were hurt too recently/)
    await fire(ctx, admin, 'hof link send Visitor in')
    await hurt.catch(() => {})
    await ctx.sync(admin, [visitor], 10)
    assert(near(await ctx.position(admin, 'Visitor'), middle({ x: 100, y: FLOOR, z: 92 }), 0.6), 'the hurt visitor stays')
    await fire(ctx, admin, 'hof link send Visitor in --checks none')
    await waitNear(ctx, admin, 'Visitor', middle(HALL_SPAWN), 2)

    // A player without the node can't send anyone.
    await ctx.command(ann, '/hof link send Ann hof-s3', /Unknown or incomplete command|permission|isn't a command|Incorrect argument/)
  }
}
