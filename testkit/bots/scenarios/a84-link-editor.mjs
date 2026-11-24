// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A84. An admin sneak-right-clicking a trigger with an empty hand gets the link editor and takes
// nothing; a player who isn't a link admin sneak-clicking it takes the link; through the editor's
// buttons the admin changes where it leads, its name and who may take it, each written to
// links.yml and audited; a button pressed after the admin lost the node is refused (M8 plan, The
// link editor).
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { FOLLOWED, link, linkInFile, setBlock } from '../lib/links.mjs'
import { hallToVisit, middle, near, standAt, waitNear } from '../lib/visits.mjs'

const SPOT = { x: 150, y: FLOOR, z: 160 }
const S3 = { x: 300, y: FLOOR, z: 300 }
const BUTTON = { x: 10, y: FLOOR, z: 22 }

export default {
  name: 'a84-link-editor',
  async run (ctx) {
    const admin = await ctx.admin()
    const visitor = await ctx.join('Visitor')
    await hallToVisit(ctx, admin, { sealed: null })
    await ctx.command(admin, '/forceload add 140 150 160 170', /^(Marked|No chunks were marked)/)
    await ctx.command(admin, '/forceload add 290 290 310 310', /^(Marked|No chunks were marked)/)
    await ctx.command(admin, `/season spawn set at ${S3.x} ${S3.y} ${S3.z}`, /^Season 3's spawn is now /)
    await ctx.command(admin, '/season config set links.access.other everyone', /^Set links\.access\.other to everyone/)
    await setBlock(ctx, admin, BUTTON, 'minecraft:stone_button[face=floor]')
    await link(ctx, admin, `add edited from ${BUTTON.x} ${BUTTON.y} ${BUTTON.z} to ${SPOT.x} ${SPOT.y} ${SPOT.z}`, /^Link edited leads to /)

    // The admin's sneak-click opens the editor and takes nothing.
    await ctx.command(admin, '/clear', /Removed|No items/)
    await standAt(ctx, admin, ctx.adminName, { x: 12, y: FLOOR, z: 22 })
    await ctx.command(admin, `/scenario use ${ctx.adminName} ${BUTTON.x} ${BUTTON.y} ${BUTTON.z} sneak`, /\(the click was taken over\)$/)
    await ctx.command(admin, `/scenario dialog ${ctx.adminName}`, /^Dialog for SeasonsAdmin: link edited$/)
    assert(near(await ctx.position(admin, ctx.adminName), middle({ x: 12, y: FLOOR, z: 22 }), 0.6), 'the admin went nowhere')

    // A visitor's sneak-click takes the link.
    await standAt(ctx, admin, 'Visitor', { x: 12, y: FLOOR, z: 23 })
    const trip = ctx.waitForMessages(visitor, FOLLOWED)
    await ctx.command(admin, `/scenario use Visitor ${BUTTON.x} ${BUTTON.y} ${BUTTON.z} sneak`, /^Used /)
    await trip
    await waitNear(ctx, admin, 'Visitor', middle(SPOT))

    // Save: where it leads, its name, who may take it.
    await ctx.command(admin, `/scenario dialog ${ctx.adminName} save to=season 3|name=<gold>Season three|access=op`, [
      /^Answered SeasonsAdmin's dialog: save$/,
      /^Link edited now leads to Season 3's spawn\.$/,
      /^Link edited is called Season three now\.$/,
      /^Link edited may be taken by ops\.$/
    ])
    await ctx.eventually(async () => {
      const saved = linkInFile(ctx, 'edited')
      assert(saved.to.season === 3 && saved.access === 'op' && /Season three/.test(saved.name), `links.yml has the edit: ${JSON.stringify(saved)}`)
    })
    await ctx.command(admin, `/season audit ${ctx.adminName}`, /link-access link:edited/)

    // Lead to where I stand, from the editor opened again.
    await ctx.command(admin, `/scenario use ${ctx.adminName} ${BUTTON.x} ${BUTTON.y} ${BUTTON.z} sneak`, /^Used /)
    await ctx.command(admin, `/scenario dialog ${ctx.adminName} here`, [/^Answered /, /^Link edited now leads to 12, -60, 22\.$/])

    // A button pressed after the node is gone.
    await ctx.command(admin, '/scenario permit Visitor seasons.admin.links', /^Permitted /)
    await ctx.command(admin, `/scenario use Visitor ${BUTTON.x} ${BUTTON.y} ${BUTTON.z} sneak`, /^Used /)
    await ctx.command(admin, '/scenario dialog Visitor', /^Dialog for Visitor: link edited$/)
    await ctx.command(admin, '/scenario unpermit Visitor seasons.admin.links', /^Unpermitted /)
    const refused = ctx.waitForMessages(visitor, /^You may no longer edit links\.$/)
    await ctx.command(admin, '/scenario dialog Visitor remove', /^Answered /)
    await refused
    assert(linkInFile(ctx, 'edited'), 'the link is still there')
  }
}
