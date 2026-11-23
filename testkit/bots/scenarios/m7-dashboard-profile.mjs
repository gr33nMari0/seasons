// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Profiles in a browser: Alex's own, logged in, with a season card for Season 2 (every statistic in
// words, the first Alex won, the Hall cell linking to its gallery) and archived gear as text: names,
// enchantments with roman levels (a book's stored ones too), a trim, each with one of our icons. A
// visitor sees only the summary of Sam's until web.yml makes profiles public. /web privacy shows on
// Alex's own leaderboard.
import { MINUTE, assert } from '../lib/context.mjs'
import { buildGallery } from '../lib/hall.mjs'
import { dashboard, loginOnPage, openPage } from '../lib/browser.mjs'
import { uuid } from '../lib/web.mjs'

export default {
  name: 'm7-dashboard-profile',
  timeoutMs: 4 * MINUTE,
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    await ctx.join('Sam')
    const { origin } = await dashboard(ctx, admin)
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await buildGallery(ctx, admin, { cells: 3 })
    await ctx.command(admin, '/advancement grant Alex only minecraft:story/mine_stone', /^Granted the advancement/)
    await ctx.command(admin, '/give Alex minecraft:diamond_sword[custom_name="Edge",enchantments={sharpness:5,unbreaking:3}]', /^Gave 1 /)
    await ctx.command(admin, '/give Alex minecraft:enchanted_book[stored_enchantments={mending:1}]', /^Gave 1 /)
    await ctx.command(admin, '/give Alex minecraft:diamond_helmet[trim={pattern:"minecraft:coast",material:"minecraft:gold"}]', /^Gave 1 /)
    await ctx.command(admin, '/give Alex minecraft:bread 12', /^Gave 12 /)
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)

    // Alex, logged in, on their own profile.
    const page = await openPage(ctx, origin, `/player/${uuid('Alex')}`)
    const summary = await page.waitForWidget('profile', /Season 2/)
    assert(!/Play time/i.test(summary), `a summary before logging in: ${summary}`)
    await loginOnPage(ctx, page, alex)
    const card = await page.waitForWidget('profile', /Play time/i)
    assert(/Jumps?/i.test(card), `every statistic, in words: ${card}`)
    assert(/Stone Age/.test(card), `the first Alex won: ${card}`)
    const cell = page.page.locator('[data-widget="profile"] a[href="/hall/2"]')
    assert(await cell.count() >= 1, 'the Hall cell links to its gallery')

    // Archived gear as text.
    const gear = await page.waitForWidget('profile', /Edge/)
    assert(/Diamond Sword/.test(gear) && /Sharpness V\b/.test(gear) && /Unbreaking III\b/.test(gear), `the sword: ${gear}`)
    assert(/Enchanted Book/.test(gear) && /Mending/.test(gear), `the book's stored enchantment: ${gear}`)
    assert(/Diamond Helmet/.test(gear) && /Coast/.test(gear) && /Gold/.test(gear), `the helmet's trim: ${gear}`)
    assert(/12 × Bread|Bread × 12|12 Bread/.test(gear), `a stack's count: ${gear}`)
    const icons = await page.page.locator('[data-widget="profile"] [data-gear] svg use').evaluateAll((all) => all.map((u) => u.getAttribute('href')))
    assert(icons.some((h) => /#sword$/.test(h)) && icons.some((h) => /#helmet$/.test(h)) && icons.some((h) => /#book$/.test(h)) && icons.some((h) => /#food$/.test(h)), `our own icons by kind: ${JSON.stringify(icons)}`)
    assert(icons.every((h) => h.startsWith('/app/icons.svg#')), `icons are ours: ${JSON.stringify(icons)}`)

    // /web privacy shows on Alex's own leaderboard.
    await ctx.command(alex, '/web privacy', /./)
    await page.goto('/')
    await page.waitForWidget('leaderboards', /hidden/i)

    // A visitor sees only Sam's summary, until profiles are public.
    const visitor = await openPage(ctx, origin, `/player/${uuid('Sam')}`)
    const samSummary = await visitor.waitForWidget('profile', /Sam/)
    assert(!/Play time/i.test(samSummary), `a visitor sees Sam's summary: ${samSummary}`)
    await dashboard(ctx, admin, 'visibility:\n  profiles: public\n  hall.gear: public\n')
    await visitor.goto(`/player/${uuid('Sam')}`)
    await visitor.waitForWidget('profile', /Play time/i)
    page.check('the profile page')
    visitor.check('a visitor\'s profile page')
  }
}
