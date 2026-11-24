// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A83. Points can be added, moved, renamed, listed, shown, gone to and removed; renaming a link
// rewrites every link leading to it; removing a point or link still used is refused, and --force
// leaves its users leading nowhere; info shows a link's triggers, access, pair and landing; lists
// filter by destination and nearness; /hof undo puts back the last links edit (M8 plan, Commands).
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { linksFile } from '../lib/hub.mjs'
import { link, linkInFile, setBlock } from '../lib/links.mjs'
import { hallToVisit, middle, standAt, waitNear } from '../lib/visits.mjs'

const BLOCK = { x: 10, y: FLOOR, z: 22 }

export default {
  name: 'a83-links-crud',
  async run (ctx) {
    const admin = await ctx.admin()
    await hallToVisit(ctx, admin, { sealed: null })
    await ctx.command(admin, '/forceload add 140 140 170 170', /^(Marked|No chunks were marked)/)

    // Points.
    const point = (words, expect) => ctx.command(admin, `/hof point ${words}`, expect)
    await point('add steps 150 -60 150 90', /^Point steps is at 150, -60, 150\.$/)
    await point('add steps 151 -60 151', /^There's already a point steps\.$/)
    await point('add yard 140 -60 140 142 -60 142', /^Point yard spans 140, -60, 140 to 142, -60, 142\.$/)
    await point('move steps 152 -60 152', /^Point steps moved to 152, -60, 152\.$/)
    await point('list', [/^Point steps at 152, -60, 152$/, /^Point yard at 140, -60, 140$/])
    await point('info steps', /^Point steps at 152, -60, 152$/)
    await point('rename steps stairs', /^Point steps is now stairs\.$/)
    await point('tp stairs', /^You're there\.$/)
    await waitNear(ctx, admin, ctx.adminName, middle({ x: 152, y: FLOOR, z: 152 }))
    await point('info nothing', /^There's no point nothing\.$/)

    // Links using them.
    await setBlock(ctx, admin, BLOCK, 'minecraft:lodestone')
    await link(ctx, admin, `add a from ${BLOCK.x} ${BLOCK.y} ${BLOCK.z} to point stairs --name <gold>Stairs`, /^Link a leads to point stairs, /)
    await link(ctx, admin, 'add b to link a', /^Link b leads to link a, /)
    await point('remove stairs', /^Point stairs is where a lead; change those first, or add --force\.$/)
    await link(ctx, admin, 'remove a', /^Link a is where b lead; remove or change those first, or add --force\.$/)

    // Renaming a link rewrites the link that leads to it.
    await link(ctx, admin, 'rename a alpha', /^Link a is now alpha\.$/)
    assert(linkInFile(ctx, 'b').to.link === 'alpha', `b now leads to alpha: ${JSON.stringify(linkInFile(ctx, 'b'))}`)
    await link(ctx, admin, 'name alpha <aqua>The stairs', /^Link alpha is called The stairs now\.$/)
    await link(ctx, admin, 'access alpha permission', /^Link alpha may be taken by holders of seasons\.link\.use\.alpha\.$/)
    await link(ctx, admin, 'when alpha after-homecoming', /^Link alpha may be taken after Homecoming\.$/)
    await link(ctx, admin, 'info alpha', [
      /^Link alpha at 10, -60, 22: to point stairs$/,
      /^ name: The stairs$/,
      /^ 1\. the block at 10, -60, 22, clicked$/,
      /^ taken by holders of seasons\.link\.use\.alpha, after Homecoming$/
    ])

    // Lists: by destination and by nearness.
    await link(ctx, admin, 'list to point stairs', /^Link alpha at 10, -60, 22: to point stairs$/)
    await standAt(ctx, admin, ctx.adminName, { x: 12, y: FLOOR, z: 24 })
    await link(ctx, admin, 'list near', /^Link alpha at 10, -60, 22: to point stairs$/)

    // Undo puts back the last links edit.
    await link(ctx, admin, 'remove b', /^Removed link b\.$/)
    await ctx.command(admin, '/hof undo', /^Put links\.yml back as it was before your last edit\.$/)
    assert(linkInFile(ctx, 'b'), 'b is back')

    // --force leaves the users leading nowhere.
    await point('remove stairs --force', /^Removed point stairs; alpha now leads nowhere\.$/)
    assert(!linksFile(ctx).points?.stairs, 'the point is gone')
    assert(!linkInFile(ctx, 'alpha').to, 'alpha leads nowhere')
    await link(ctx, admin, 'remove alpha --force', /^Removed link alpha; b now leads nowhere\.$/)

    // Every change is audited, with no position in an entry's target.
    await ctx.command(admin, `/season audit ${ctx.adminName}`, /link-remove link:alpha/)
  }
}
