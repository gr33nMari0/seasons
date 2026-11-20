// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// /museum add <anchor> [label] takes the op's held item into the store and shows it on the plinth
// as a copy, with a plaque line (label, season, credit); the runbook's original egg goes on the
// first egg plinth this way. Refused without an item, for an unknown anchor and for a plinth that
// already holds something. /museum remove gives the item back whole, or keeps it while the op's
// inventory is full. Both are audited, and the plinths are still there after a restart.
import { assert } from '../lib/context.mjs'
import { carried } from '../lib/vault.mjs'
import { PLINTHS, plaqueText, shownItem, shownItemData, writeMuseum } from '../lib/museum.mjs'
import { forceload } from '../lib/zones.mjs'

export default {
  name: 'm4-museum',
  sessions: [
    async (ctx) => {
      const admin = await ctx.admin()
      await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
      await writeMuseum(ctx, admin)
      await ctx.command(admin, '/clear SeasonsAdmin', /./)

      await ctx.command(admin, '/museum add museum:core', /^Hold the item you want to put in the museum\.$/)
      await ctx.command(admin, '/give SeasonsAdmin minecraft:heavy_core', /^Gave 1 /)
      await ctx.command(admin, '/museum add museum:nowhere', /^There is no museum anchor museum:nowhere\.$/)
      await ctx.command(admin, '/museum add museum:core Heavy core', /^Put minecraft:heavy_core in museum:core\.$/)
      assert(await carried(ctx, admin, 'SeasonsAdmin', 'heavy_core') === 0, 'the heavy core left the op')
      await ctx.eventually(async () => assert(await shownItem(ctx, admin, PLINTHS.core) === 'minecraft:heavy_core', 'the plinth shows the heavy core'))
      const plaque = await plaqueText(ctx, admin, PLINTHS.core)
      assert(/Heavy core · Season 2 · SeasonsAdmin/.test(plaque), `the plaque: ${plaque}`)
      assert(/seasons:archived/.test(await shownItemData(ctx, admin, PLINTHS.core)), 'only a tagged copy is shown')

      await ctx.command(admin, '/give SeasonsAdmin minecraft:stone', /^Gave 1 /)
      await ctx.command(admin, '/museum add museum:core', /^museum:core already holds minecraft:heavy_core\. Take it out first with \/museum remove museum:core\.$/)
      assert(await carried(ctx, admin, 'SeasonsAdmin', 'stone') === 1, 'the stone stays with the op')
      await ctx.command(admin, '/clear SeasonsAdmin', /./)

      // The runbook: the original dragon egg goes on the first egg plinth.
      await ctx.command(admin, '/give SeasonsAdmin minecraft:dragon_egg', /^Gave 1 /)
      await ctx.command(admin, '/museum add museum:egg-s2 The original egg', /^Put minecraft:dragon_egg in museum:egg-s2\.$/)
      await ctx.eventually(async () => assert(await shownItem(ctx, admin, PLINTHS.eggS2) === 'minecraft:dragon_egg', 'the egg plinth shows the egg'))
      assert(/The original egg · Season 2 · SeasonsAdmin/.test(await plaqueText(ctx, admin, PLINTHS.eggS2)), 'the egg plinth has its plaque')

      // A full inventory keeps the heavy core in the museum.
      await ctx.command(admin, '/give SeasonsAdmin minecraft:stone 2304', /^Gave 2304 /)
      await ctx.command(admin, '/museum remove museum:core', /^Your inventory is full, so minecraft:heavy_core stays in museum:core\.$/)
      assert(await shownItem(ctx, admin, PLINTHS.core) === 'minecraft:heavy_core', 'still shown')
      await ctx.command(admin, '/clear SeasonsAdmin', /./)
      await ctx.command(admin, '/museum remove museum:core', /^Took minecraft:heavy_core from museum:core\.$/)
      assert(await carried(ctx, admin, 'SeasonsAdmin', 'heavy_core') === 1, 'the op has the heavy core back')
      await ctx.eventually(async () => assert(await shownItem(ctx, admin, PLINTHS.core) === null, 'the plinth is empty'))
      assert(await plaqueText(ctx, admin, PLINTHS.core) === null, 'and has no plaque')
      await ctx.command(admin, '/museum remove museum:core', /^museum:core holds nothing\.$/)

      await ctx.command(admin, '/season audit SeasonsAdmin', [/museum add/, /museum remove/])
    },
    async (ctx) => {
      const admin = await ctx.admin()
      await forceload(ctx, admin, { x: 207, y: -60, z: 305 }, 16)
      await ctx.eventually(async () => assert(await shownItem(ctx, admin, PLINTHS.eggS2) === 'minecraft:dragon_egg', 'the egg is still shown after the restart'))
      await ctx.command(admin, '/clear SeasonsAdmin', /./)
      await ctx.command(admin, '/museum remove museum:egg-s2', /^Took minecraft:dragon_egg from museum:egg-s2\.$/)
      assert(await carried(ctx, admin, 'SeasonsAdmin', 'dragon_egg') === 1, 'the op has the original egg')
    }
  ]
}
