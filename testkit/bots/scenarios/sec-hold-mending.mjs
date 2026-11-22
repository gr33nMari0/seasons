// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Security review M1. While a player is held for archiving, nothing changes what they carry: XP
// orbs aren't picked up, so a mending item they wear isn't repaired between the copy and the clear,
// and the archive goes through. Without the hold covering it, the repair changes the item, the
// inventory no longer matches the copy and the archive fails.
import { assert } from '../lib/context.mjs'

const PLAYERS = ['Alex', 'Sam', 'Pat']

export default {
  name: 'sec-hold-mending',
  async run (ctx) {
    const admin = await ctx.admin()
    for (const name of PLAYERS) await ctx.join(name)
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)

    for (const name of PLAYERS) {
      await ctx.command(admin, `/item replace entity ${name} armor.chest with minecraft:diamond_chestplate[damage=500,enchantments={"minecraft:mending":1}]`, /^Replaced \d+ slot/)
      // Orbs picked up one every two ticks: some are always being picked up while the archive's
      // files are written.
      for (let i = 0; i < 40; i++) {
        await ctx.command(admin, `/execute at ${name} run summon minecraft:experience_orb ~ ~ ~ {Value:1}`, /^Summoned new /)
      }
    }

    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\. Welcome to the Intermission\.$/, 20_000)

    for (const name of PLAYERS) {
      const archive = await ctx.archive(admin, name, 2)
      assert(archive.archivedThrough === 2 && archive.items === 1, `${name} is archived with the chestplate: ${JSON.stringify(archive)}`)
      assert(await ctx.itemCount(admin, name) === 0, `${name} carries nothing`)
    }
  }
}
