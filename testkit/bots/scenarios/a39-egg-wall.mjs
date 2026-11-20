// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @isolated the End's dragon fight, which the reset can't put back
// A39. At season end, the museum's egg plinth for that season shows a display egg with the slayer's
// name. Also: the dry run warns while the season has no egg plinth; a season without a slayer
// shows nothing on its plinth; the display is a copy tagged as archived, never a real egg;
// reconciliation leaves it alone and draws it again when it is gone.
import { SECOND, assert } from '../lib/context.mjs'
import { PLINTHS, plaqueText, shownItem, shownItemData, writeMuseum } from '../lib/museum.mjs'
import { launchFrontier } from '../lib/zones.mjs'
import { EGG_WAIT, hurt, toTheEnd, waitForDragon } from '../lib/dragon.mjs'

export default {
  name: 'a39-egg-wall',
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    await launchFrontier(ctx, admin)

    // Without an egg plinth for Season 3, the dry run says so; with one, it doesn't.
    await ctx.command(admin, '/season end --dry-run', /^Museum: no egg plinth for Season 3, so its dragon egg won't be shown\. Add egg: 3 to a trophy anchor in a museum gallery\.$/)
    await writeMuseum(ctx, admin)
    const quiet = ctx.quiet(admin, /^Museum: no egg plinth/)
    await ctx.command(admin, '/season end --dry-run', /^Dry run of \/season end for Season 3/)
    await ctx.sync(admin)
    quiet.verify()

    // Pat slays Season 3's dragon.
    await toTheEnd(ctx, admin, ['Pat'])
    await waitForDragon(ctx, admin)
    const egg = ctx.waitForMessages(pat, /^The dragon egg is yours\.$/, EGG_WAIT, 'the egg')
    await hurt(ctx, admin, 'Pat', 1000, 'melee')
    await egg
    assert(await shownItem(ctx, admin, PLINTHS.eggS3) === null, 'the plinth is empty while the season runs')

    // The season ends: the Season 3 plinth shows the egg and who slew the dragon.
    await ctx.command(admin, '/season end --now', /^Ending Season 3 now/)
    await ctx.waitForMessages(admin, /^Season 3 has ended\./, 20 * SECOND)
    await ctx.eventually(async () => assert(await shownItem(ctx, admin, PLINTHS.eggS3) === 'minecraft:dragon_egg', 'the egg plinth shows a dragon egg'), 10 * SECOND)
    const plaque = await plaqueText(ctx, admin, PLINTHS.eggS3)
    assert(/Season 3 · slain by Pat/.test(plaque), `the plaque names the slayer: ${plaque}`)
    const data = await shownItemData(ctx, admin, PLINTHS.eggS3)
    assert(/seasons:archived/.test(data), `the egg shown is a copy tagged as archived: ${data}`)
    // Season 2 had no slayer, so its plinth stays empty.
    assert(await shownItem(ctx, admin, PLINTHS.eggS2) === null, 'the Season 2 plinth is empty')

    // Reconciliation keeps the egg, and draws it again when it is gone.
    await ctx.command(admin, '/hof reconcile museum', /^Reconciled museum: 0 repairs\.$/, 20 * SECOND)
    await ctx.command(admin, '/kill @e[type=minecraft:item_display]', /^Killed /)
    await ctx.command(admin, '/kill @e[type=minecraft:text_display]', /^Killed /)
    await ctx.command(admin, '/hof reconcile museum', /^Reconciled museum: 2 repairs\.$/, 20 * SECOND)
    assert(await shownItem(ctx, admin, PLINTHS.eggS3) === 'minecraft:dragon_egg', 'the egg is back')
    assert(/Season 3 · slain by Pat/.test(await plaqueText(ctx, admin, PLINTHS.eggS3)), 'the plaque is back')
  }
}
