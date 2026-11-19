// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Access mode bounds (questions 168): a cell's bounds are an edge only its owner crosses, while
// they may decorate it. A visitor walking or teleported in is refused and stays out; the owner walks
// in during the window and is moved to the gallery entry when it closes; /hof <owner> lands at the
// gallery entry; a bypass holder walks in; the refusals are audited.
import { assert } from '../lib/context.mjs'
import { FLOOR, buildGallery, inside } from '../lib/hall.mjs'
import { audit } from '../lib/protection.mjs'
import { hof, middle, move, standAt, waitNear } from '../lib/visits.mjs'
import { permit } from '../lib/zones.mjs'

const FILE = 'world/dimensions/minecraft/overworld/seasons/galleries/season-2.yml'
const CLOSED = /^Only its owner goes into that cell, while decorating it\.$/
const MOVED_OUT = /^Only its owner can be in that cell, while decorating it, so you're at the gallery entry\.$/
const GALLERY_ENTRY = { x: 21.5, y: FLOOR, z: 21.5 }

export default {
  name: 'm2-access-bounds',
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    const sam = await ctx.join('Sam')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    const cells = await buildGallery(ctx, admin, { cells: 3 })
    const c1 = cells[0]
    ctx.writeFile(FILE, ctx.readFile(FILE).replace(/^cells:/m, 'access: bounds\ncells:'))
    await ctx.command(admin, '/season reload', /^Reloaded /)
    await ctx.command(admin, '/hof cell assign season-2:c1 Alex', /^Assigned season-2:c1 to Alex for Season 2\.$/)
    await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: /)

    // The corridor in front of the cells is the gallery's, open to everyone.
    const corridor = { x: c1.x0 + 2, y: FLOOR, z: c1.z0 - 1 }
    const inCell = { x: c1.x0 + 2.5, y: FLOOR, z: c1.z0 + 2.5 }
    await standAt(ctx, admin, 'Sam', corridor)

    // A visitor walking in is refused at the edge.
    let told = ctx.waitForMessages(sam, CLOSED)
    await move(ctx, admin, 'Sam', inCell, 'walk')
    await told
    assert(!inside(c1, await ctx.position(admin, 'Sam')), 'Sam stays out of c1')

    // So is a teleport in.
    told = ctx.waitForMessages(sam, CLOSED)
    await ctx.command(admin, `/tp Sam ${inCell.x} ${inCell.y} ${inCell.z}`, /^Teleported /)
    await told
    assert(!inside(c1, await ctx.position(admin, 'Sam')), 'a teleport into c1 is refused')

    // The owner walks in while decorating.
    await standAt(ctx, admin, 'Alex', corridor)
    await move(ctx, admin, 'Alex', inCell, 'walk')
    await ctx.eventually(async () => assert(inside(c1, await ctx.position(admin, 'Alex')), 'Alex walks into their own cell'))

    // Once the window closes they may not be in it, and are moved to the gallery entry.
    told = ctx.waitForMessages(alex, MOVED_OUT)
    await ctx.command(admin, '/season decorate close', /^The decorating window for Season 2 is closed\.$/)
    await told
    await waitNear(ctx, admin, 'Alex', GALLERY_ENTRY)

    // /hof <owner> lands at the gallery entry, not inside the cell.
    await standAt(ctx, admin, 'Sam', { x: c1.x0 + 16, y: FLOOR, z: c1.z0 - 1 })
    await hof(ctx, sam, '/hof Alex', /^Welcome to the Hall of Fame\./)
    await waitNear(ctx, admin, 'Sam', GALLERY_ENTRY)

    // A bypass holder walks in.
    await permit(ctx, admin, 'Sam', 'seasons.hof.bypass')
    await standAt(ctx, admin, 'Sam', corridor)
    await move(ctx, admin, 'Sam', inCell, 'walk')
    await ctx.eventually(async () => assert(inside(c1, await ctx.position(admin, 'Sam')), 'a bypass holder walks in'))
    await waitNear(ctx, admin, 'Sam', middle({ x: c1.x0 + 2, y: FLOOR, z: c1.z0 + 2 }))

    const blocked = audit(ctx).filter((e) => e.action === 'hall-blocked' && e.actor === 'Sam' && e.details.startsWith('enter'))
    assert(blocked.some((e) => e.target === c1.key), `Sam's refused entries are audited: ${JSON.stringify(blocked)}`)
  }
}
