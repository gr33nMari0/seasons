// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The award list is config.yml's awards section: an award of a server's own by hand edit (any
// General-tab statistic, named in messages.yml or after its id), a default one turned off with
// measure: off, and settings read and changed with /season config. A measure Seasons doesn't know,
// an item the server doesn't know and an id that isn't a slug are refused with their key path.
import { SECOND } from '../lib/context.mjs'
import { setStat } from '../lib/awards.mjs'

const CONFIG = 'plugins/Seasons/config.yml'
const OURS = [
  'schema-version: 1',
  'awards:',
  '  deepest-pockets: { measure: off }',
  '  most-jumps: { measure: minecraft:jump, item: rabbit_foot }',
  ''
].join('\n')

export default {
  name: 'm4-awards-config',
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)

    ctx.writeFile(CONFIG, OURS)
    await ctx.command(admin, '/season reload', /^Reloaded /)
    await ctx.command(admin, '/season config get awards.most-jumps.measure', /^awards\.most-jumps\.measure = minecraft:jump$/)
    await ctx.command(admin, '/season config get awards.deepest-pockets', [/^awards\.deepest-pockets\.measure = off$/, /^awards\.deepest-pockets\.item = diamond$/])
    await ctx.command(admin, '/season config set awards.most-deaths.item wither_skeleton_skull', /^Set awards\.most-deaths\.item to wither_skeleton_skull in config\.yml \(it was skeleton_skull\)\.$/)
    await ctx.command(admin, '/season config set awards.most-deaths.measure kills', /^awards\.most-deaths\.measure wasn't changed: config\.yml, at awards\.most-deaths\.measure: kills isn't a measure: /)
    await ctx.command(admin, '/season config set awards.most-jumps.item not_an_item', /^awards\.most-jumps\.item wasn't changed: config\.yml, at awards\.most-jumps\.item: not_an_item isn't an item this server knows$/)

    // An id that isn't a slug: the reload fails and the previous config stays live.
    const good = ctx.readFile(CONFIG)
    ctx.writeFile(CONFIG, good + '  Most Hops: { measure: minecraft:jump, item: rabbit_foot }\n')
    await ctx.command(admin, '/season reload', /awards\.Most Hops: award ids are lower-case letters, digits and dashes/)
    ctx.writeFile(CONFIG, good)
    await ctx.command(admin, '/season reload', /^Reloaded /)

    // The season's awards follow the list: Most jumps, named after its id, and no Deepest pockets.
    await setStat(ctx, admin, 'Alex', 'minecraft:jump', 40)
    await setStat(ctx, admin, 'Alex', 'minecraft:mined:minecraft:diamond_ore', 3)
    const announced = ctx.waitForMessages(alex, [/^Season 2 awards:$/, /^Most jumps: Alex \(40\)$/], 30 * SECOND, 'the awards')
    const off = ctx.quiet(alex, /^Deepest pockets:/)
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await announced
    await ctx.sync(admin, [alex], 20)
    off.verify()
  }
}
