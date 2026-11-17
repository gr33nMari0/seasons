// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A47 (first half; M1 part 2 adds the rest). On a server that has never run Seasons, /season init 2
// records Season 2 as the season in progress. A second /season init is refused.
export default {
  name: 'a47-init',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.command(admin, '/season status', /^No season recorded yet\b/)
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 as the season in progress\.$/)
    await ctx.command(admin, '/season status', [/^Season 2$/, /^Phase: Active, Open stage$/])
    await ctx.command(admin, '/season init 3', /^Season 2 is already recorded\b/)
    await ctx.command(admin, '/season init 2', /^Season 2 is already recorded\b/)
  }
}
