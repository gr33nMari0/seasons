// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A54. A link in the hub takes a visitor to the current season's spawn; a link to land in a locked
// zone is refused before Homecoming and works after it; a link at a season's spawn takes a player
// to the Hall spawn with no bed near, and /hof leave returns them to it; a link block outside the
// Hall can't be broken; stepping on a pressure plate link works (questions 248 and 249). Season 2
// ends and Season 3 launches at SPAWN; the base zone stands in for Season 1's locked land. From
// review of #26: a link outside the Hall stays when the block holding it up is broken, and /hof
// leave back onto a pressure plate link doesn't take it again.
import { assert } from '../lib/context.mjs'
import { FLOOR, breakBlock, buildGallery, gameMode, use } from '../lib/hall.mjs'
import { HALL_SPAWN, HUB, addLink, hallFile, inBox, markHub } from '../lib/hub.mjs'
import { hof, middle, standAt, waitNear } from '../lib/visits.mjs'
import { click } from '../lib/wand.mjs'
import { BASE, INSIDE, SPAWN, drawBase, forceload, inBase } from '../lib/zones.mjs'

const TO_S3 = { x: 10, y: FLOOR, z: 22 }
const TO_S1 = { x: 10, y: FLOOR, z: 24 }
const PLATE = { x: 10, y: FLOOR, z: 28 }
const BACK = { x: SPAWN.x + 3, y: FLOOR, z: SPAWN.z }
const TAKEN = /\(the click was taken over\)$/

export default {
  name: 'a54-links',
  async run (ctx) {
    const admin = await ctx.admin()
    const visitor = await ctx.join('Visitor')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await buildGallery(ctx, admin, { cells: 2 })
    await markHub(ctx, admin)
    await forceload(ctx, admin, SPAWN, 12)
    await forceload(ctx, admin, BASE, 40)

    // Links in the hub: one to Season 3's spawn, one to Season 1's land (a spot inside the base),
    // and a pressure plate to Season 3's spawn too.
    await ctx.command(admin, `/setblock ${TO_S3.x} ${TO_S3.y} ${TO_S3.z} minecraft:lodestone`, /^Changed the block/)
    await ctx.command(admin, `/setblock ${TO_S1.x} ${TO_S1.y} ${TO_S1.z} minecraft:lodestone`, /^Changed the block/)
    await ctx.command(admin, `/setblock ${PLATE.x} ${PLATE.y} ${PLATE.z} minecraft:stone_pressure_plate`, /^Changed the block/)
    await addLink(ctx, admin, 's3', TO_S3, 'season 3', /^Link s3 at 10, -60, 22 leads to Season 3's spawn\.$/)
    await addLink(ctx, admin, 's1', TO_S1, '', /^Link s1 at 10, -60, 24 leads nowhere yet: stand where it should lead and run \/hof link to s1\.$/)
    await ctx.command(admin, `/hof link to s1 ${INSIDE.x} ${INSIDE.y} ${INSIDE.z}`, new RegExp(`^Link s1 now leads to ${INSIDE.x}, ${INSIDE.y}, ${INSIDE.z}\\.$`))
    await addLink(ctx, admin, 'plate', PLATE, 'season 3')
    await ctx.command(admin, '/hof link list', [/^Link s1 at 10, -60, 24: to 510, -60, 500$/, /^Link s3 at 10, -60, 22: to Season 3's spawn$/, /^Link plate at 10, -60, 28: to Season 3's spawn$/])
    assert(hallFile(ctx).links?.s3?.to?.season === 3, `hall.yml has the links: ${JSON.stringify(hallFile(ctx))}`)

    // Season 3 launches at SPAWN, with the base locked until Homecoming.
    // Season 3's spawn before the end: in the Intermission everyone stays in the Hall.
    await standAt(ctx, admin, 'SeasonsAdmin', SPAWN)
    await ctx.command(admin, '/season spawn set', /^Season 3's spawn is now /)
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
    await ctx.command(admin, '/season launch', /^Season 3 has begun!$/, 30_000)
    await drawBase(ctx, admin)
    await standAt(ctx, admin, 'SeasonsAdmin', { x: SPAWN.x - 5, y: FLOOR, z: SPAWN.z })

    // At Season 3's spawn, a link back to the Hall: no bed needed.
    await ctx.command(admin, `/setblock ${BACK.x} ${BACK.y} ${BACK.z} minecraft:stone_button[face=floor]`, /^Changed the block/)
    await addLink(ctx, admin, 'hall', BACK, 'hall', /^Link hall at 3003, -60, 0 leads to the Hall spawn\.$/)
    const home = { x: SPAWN.x + 2, y: FLOOR, z: SPAWN.z }
    await standAt(ctx, admin, 'Visitor', home)
    await ctx.advance(admin, 6_000) // past the launch's arrival immunity and the rate limit
    // The link block outside the Hall can't be broken.
    assert(!await breakBlock(ctx, admin, 'Visitor', BACK), 'a visitor can\'t break a link block outside the Hall')
    // Nor knock it off by breaking the block it sits on.
    const under = { x: BACK.x, y: BACK.y - 1, z: BACK.z }
    assert(await breakBlock(ctx, admin, 'Visitor', under), 'the block under the link is anyone\'s to break')
    await ctx.sync(admin, [visitor], 10)
    assert(await ctx.holds(admin, `block ${BACK.x} ${BACK.y} ${BACK.z} minecraft:stone_button`), 'the link button stays')
    await ctx.command(admin, `/setblock ${under.x} ${under.y} ${under.z} minecraft:stone`, /^Changed the block/)
    const line = await click(ctx, admin, 'Visitor', BACK)
    assert(TAKEN.test(line), `the button click is taken over: ${line}`)
    await ctx.waitForMessages(visitor, /^Welcome to the Hall of Fame\. \/hof leave takes you back\.$/)
    await waitNear(ctx, admin, 'Visitor', middle(HALL_SPAWN))
    await ctx.eventually(async () => assert(await gameMode(ctx, admin, 'Visitor') === 2, 'adventure in the Hall'))

    // /hof leave returns them to the link they came from.
    await hof(ctx, visitor, '/hof leave', /^Back where you left\.$/)
    await waitNear(ctx, admin, 'Visitor', middle(home))

    // Back in the Hall, the link to Season 1's land is refused before Homecoming.
    await ctx.advance(admin, 6_000)
    await use(ctx, admin, 'Visitor', BACK)
    await waitNear(ctx, admin, 'Visitor', middle(HALL_SPAWN))
    await ctx.advance(admin, 6_000)
    const refused = ctx.waitForMessages(visitor, /^That leads into land locked until Homecoming\.$/)
    await use(ctx, admin, 'Visitor', TO_S1)
    await refused
    assert(inBox(HUB, await ctx.position(admin, 'Visitor')), 'the visitor stays in the hub')

    // The link to Season 3's spawn takes them there, in survival, with no saved spot left.
    await ctx.advance(admin, 6_000)
    const followed = ctx.waitForMessages(visitor, /^You followed the link\.$/, 20_000)
    await use(ctx, admin, 'Visitor', TO_S3)
    await followed
    await waitNear(ctx, admin, 'Visitor', middle(SPAWN), 3)
    await ctx.eventually(async () => assert(await gameMode(ctx, admin, 'Visitor') === 0, 'survival at the spawn'))
    await hof(ctx, visitor, '/hof leave', /^You aren't visiting the Hall of Fame\.$/)

    // After Homecoming the link to Season 1's land works.
    await ctx.command(admin, '/season unlock homecoming', /Homecoming/)
    await ctx.advance(admin, 6_000)
    await use(ctx, admin, 'Visitor', BACK)
    await waitNear(ctx, admin, 'Visitor', middle(HALL_SPAWN))
    await ctx.advance(admin, 6_000)
    const arrived = ctx.waitForMessages(visitor, /^You followed the link\.$/, 20_000)
    await use(ctx, admin, 'Visitor', TO_S1)
    // Once the trip has finished: until then the next link is ignored, as a second /hof would be.
    await arrived
    await ctx.eventually(async () => assert(inBase(await ctx.position(admin, 'Visitor')), 'the visitor is in Season 1\'s land'))

    // Stepping on the pressure plate in the hub.
    await ctx.advance(admin, 6_000)
    await standAt(ctx, admin, 'Visitor', home)
    await use(ctx, admin, 'Visitor', BACK)
    await waitNear(ctx, admin, 'Visitor', middle(HALL_SPAWN))
    await ctx.advance(admin, 6_000)
    await standAt(ctx, admin, 'Visitor', PLATE)
    await waitNear(ctx, admin, 'Visitor', middle(SPAWN), 3)

    // A pressure plate back to the Hall at the spawn: /hof leave returns the visitor onto it, and
    // it doesn't take them in again until they step off and on.
    const PLATE_BACK = { x: SPAWN.x - 3, y: FLOOR, z: SPAWN.z }
    await ctx.command(admin, `/setblock ${PLATE_BACK.x} ${PLATE_BACK.y} ${PLATE_BACK.z} minecraft:stone_pressure_plate`, /^Changed the block/)
    await addLink(ctx, admin, 'plate-back', PLATE_BACK, 'hall')
    await ctx.advance(admin, 6_000)
    await standAt(ctx, admin, 'Visitor', PLATE_BACK)
    await waitNear(ctx, admin, 'Visitor', middle(HALL_SPAWN))
    await ctx.advance(admin, 6_000)
    await hof(ctx, visitor, '/hof leave', /^Back where you left\.$/)
    await waitNear(ctx, admin, 'Visitor', middle(PLATE_BACK))
    const stays = ctx.quiet(visitor, /^Welcome to the Hall of Fame/)
    await ctx.advance(admin, 6_000)
    await ctx.sync(admin, [visitor], 40)
    stays.verify()
    await waitNear(ctx, admin, 'Visitor', middle(PLATE_BACK))
  }
}
