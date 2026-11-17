// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A4 (M1 part 2's half; ending in their own cell comes with the Hall in part 3). After /season end,
// every online player has an empty inventory and ender chest, zero XP, no advancements except
// recipe unlocks, no respawn point and full health, and their gear is in their archive.
import { assert } from '../lib/context.mjs'
import { ENDER_ITEMS, GEAR_ITEMS, expectEmpty, giveGear, giveProgress } from '../lib/gear.mjs'

export default {
  name: 'a04-end-state',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Alex')
    await ctx.join('Sam')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    for (const name of ['Alex', 'Sam']) {
      await giveGear(ctx, admin, name)
      await giveProgress(ctx, admin, name)
    }
    await ctx.command(admin, '/damage Alex 6', /^Applied 6/)
    assert(await ctx.itemCount(admin, 'Alex') === GEAR_ITEMS, 'Alex carries the gear before the end')

    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\. Welcome to the Intermission\.$/, 20_000)

    for (const name of ['Alex', 'Sam']) {
      await expectEmpty(ctx, admin, name, assert)
      assert(/^0 experience levels|has 0 experience levels$/.test(await ctx.command(admin, `/xp query ${name} levels`, /experience levels$/)), `${name} has no XP levels`)
      assert(/has 0 experience points$/.test(await ctx.command(admin, `/xp query ${name} points`, /experience points$/)), `${name} has no XP points`)
      assert(await ctx.entityData(admin, name, 'respawn') === null, `${name} has no respawn point`)
      assert(await ctx.entityData(admin, name, 'active_effects') === null, `${name} has no effects`)
      assert(await ctx.entityData(admin, name, 'Health') === '20.0f', `${name} has full health`)
      assert(!await ctx.holds(admin, `entity @a[name=${name},advancements={minecraft:story/mine_stone=true}]`), `${name} lost Stone Age`)
      assert(!await ctx.holds(admin, `entity @a[name=${name},advancements={minecraft:story/root=true}]`), `${name} lost Minecraft`)
      assert(await ctx.holds(admin, `entity @a[name=${name},advancements={minecraft:recipes/building_blocks/stone_slab=true}]`), `${name} kept the recipe unlock`)

      const archive = await ctx.archive(admin, name, 2)
      assert(archive.present && archive.items === GEAR_ITEMS, `${name}'s gear is in the archive: ${JSON.stringify(archive)}`)
      assert(archive.vault === ENDER_ITEMS, `${name}'s ender chest is sealed in the vault: ${JSON.stringify(archive)}`)
      assert(archive.completed === 2, `the archive holds ${name}'s two advancements: ${JSON.stringify(archive)}`)
      assert(archive.archivedThrough === 2, `${name} is archived through Season 2`)
    }
    await ctx.command(admin, '/recipe take Alex *', /^Took \d+ recipes? from Alex/)
  }
}
