// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Galleries and cells tagged by command (the wand comes in M5), the rules they keep, a hand edit
// of the gallery file picked up by /season reload, and opening and closing the decorating window.
import { assert } from '../lib/context.mjs'
import { buildGallery, owners } from '../lib/hall.mjs'

const FILE = 'world/dimensions/minecraft/overworld/seasons/galleries/season-2.yml'

export default {
  name: 'm1-hall-commands',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    const cells = await buildGallery(ctx, admin, { cells: 2 })
    await ctx.command(admin, '/hof gallery create season-2 2', /^Gallery season-2 already exists\.$/)
    await ctx.command(admin, '/hof cell new season-2 c1', /^Cell season-2:c1 already exists\.$/)
    await ctx.command(admin, '/hof cell new nowhere c1', /^There is no gallery nowhere\.$/)

    // Galleries don't overlap; cells sit inside their gallery; anchors inside their cell.
    await ctx.command(admin, '/hof gallery create other 2', /^Created gallery other for Season 2\.$/)
    await ctx.command(admin, '/hof gallery bounds other 40 -61 25 60 -50 40', /^Gallery other would overlap season-2\.$/)
    await ctx.command(admin, '/hof cell bounds season-2:c1 0 -61 0 4 -50 4', /^Cell season-2:c1 must sit inside gallery season-2\.$/)
    await ctx.command(admin, '/hof cell anchor season-2:c1 trophy 100 -60 100', /^That anchor is outside cell season-2:c1\.$/)
    await ctx.command(admin, '/hof cell anchor season-2:c1 trophy 21 -59 27', /^Added a trophy anchor to season-2:c1 at 21, -59, 27\.$/)
    await ctx.command(admin, '/hof cell info season-2:c1', /^Cell season-2:c1: free for Season 2; anchors: figure 1, archive 1, plaque 1, trophy 1, entry 1$/)
    await ctx.command(admin, '/season validate', /^All config files are valid\.$/)

    // A cell with no figure is never handed out.
    await ctx.command(admin, '/hof cell new season-2 bare', /^Created cell bare in gallery season-2\.$/)
    await ctx.command(admin, '/hof cell bounds season-2:bare 33 -61 22 36 -55 30', /^Cell season-2:bare now spans /)

    // A broken hand edit leaves the gallery as it was; a good one is picked up by reload.
    const good = ctx.readFile(FILE)
    assert(/c1:/.test(good) && /figure/.test(good), `the gallery file holds the cells: ${good}`)
    ctx.writeFile(FILE, good + '\n  broken: [\n')
    await ctx.command(admin, '/season reload', [/^Reload failed\. The previous config stays live\.$/, /^galleries\/season-2\.yml, line \d+, column \d+:/])
    await ctx.command(admin, '/hof cell info season-2:c2', /^Cell season-2:c2: free for Season 2; /)
    ctx.writeFile(FILE, good.replace(/^(\s+)c2:$/m, '$1c9:'))
    await ctx.command(admin, '/season reload', /^Reloaded /)
    await ctx.command(admin, '/hof cell info season-2:c9', /^Cell season-2:c9: free for Season 2; anchors: figure 1/)
    await ctx.command(admin, '/hof cell info season-2:c2', /^There is no cell season-2:c2\.$/)

    await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: 1 player has cells\.$/)
    const assigned = await owners(ctx, admin, 'season-2', [cells[0], { id: 'c9' }, { id: 'bare' }])
    assert(assigned.bare === null, `the bare cell isn't handed out: ${JSON.stringify(assigned)}`)
    await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is already open\.$/)
    await ctx.command(admin, '/season decorate close', /^The decorating window for Season 2 is closed\.$/)
    await ctx.command(admin, '/season decorate close', /^The decorating window for Season 2 isn't open\.$/)
    await ctx.command(admin, '/season end --dry-run', /^Hall of Fame: \d+ free cells? in Season 2's galleries for \d+ players? still without one\.$/)
  }
}
