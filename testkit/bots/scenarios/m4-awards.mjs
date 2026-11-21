// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Awards (Stats, season cards and awards): computed at season end from the season's archives,
// ties shared, an award nobody earned (no diamonds mined, no dragon slain) given to nobody;
// announced once in chat and in one Discord post; each winner's trophies on their cell's trophy
// anchors, a line on their plaque and on their season card; the museum's plaque for the season
// lists every winner; /season firsts lists them too.
import { SECOND, assert } from '../lib/context.mjs'
import { buildGallery, cellOf, place } from '../lib/hall.mjs'
import { PLINTHS, plaqueText, shownItem, writeMuseum } from '../lib/museum.mjs'
import { addTrophies, setStat, trophies } from '../lib/awards.mjs'

const HOUR_TICKS = 20 * 60 * 60

export default {
  name: 'm4-awards',
  async run (ctx) {
    const discord = await ctx.webhook()
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    await ctx.join('Sam')
    await ctx.join('Pat')
    ctx.writeFile('plugins/Seasons/secrets.yml', `schema-version: 1\ndiscordWebhook: "${discord.url}"\n`)
    await ctx.command(admin, '/season reload', /^Reloaded /)
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    const cells = await buildGallery(ctx, admin, { cells: 4 })
    await addTrophies(ctx, admin, cells)
    await writeMuseum(ctx, admin)
    await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: /)

    // Alex and Sam tie on deaths; Sam walked furthest; Pat played longest and built furthest out;
    // Alex completed the most advancements. Nobody mined a diamond or slew the dragon.
    await setStat(ctx, admin, 'Alex', 'minecraft:deaths', 5)
    await setStat(ctx, admin, 'Sam', 'minecraft:deaths', 5)
    await setStat(ctx, admin, 'Pat', 'minecraft:deaths', 2)
    await setStat(ctx, admin, 'Sam', 'minecraft:walk_one_cm', 5_000_000)
    await setStat(ctx, admin, 'Pat', 'minecraft:play_time', 10 * HOUR_TICKS)
    await ctx.command(admin, '/advancement grant Alex from minecraft:story/root', /^Granted /)
    await ctx.command(admin, '/forceload add 2996 -4 3004 4', /^(Marked|No chunks)/)
    assert(await place(ctx, admin, 'Pat', { x: 3000, y: -60, z: 0 }), 'Pat places a block far out')

    const announced = ctx.waitForMessages(alex, [
      /^Season 2 awards:$/,
      /^Most deaths: Alex and Sam \(5\)$/,
      /^Furthest travelled: Sam \(50,000 blocks\)$/,
      /^Most dedicated: Pat \(\d+ h \d+ min\)$/,
      /^Furthest builder: Pat \(3,000 blocks\)$/,
      /^Most advancements: Alex \(\d+\)$/
    ], 30 * SECOND, 'the awards')
    const unearned = ctx.quiet(alex, /^(Deepest pockets|Dragon slayer):/)
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20 * SECOND)
    ctx.log((await announced).join('\n'))
    await ctx.sync(admin, [alex], 20)
    unearned.verify()

    // One Discord post with every award.
    const post = await discord.waitFor(/^Season 2 awards:/, 15 * SECOND)
    assert(/\nMost deaths: Alex and Sam \(5\)(\n|$)/.test(post.content), `the post names both: ${post.content}`)
    assert(!/Deepest pockets|Dragon slayer/.test(post.content), `unearned awards aren't posted: ${post.content}`)
    assert(discord.posts.filter((p) => /^Season 2 awards:/.test(p.content ?? '')).length === 1, 'posted once')

    // Trophies, in award order, on each winner's cell's trophy anchors.
    const expected = {
      Alex: ['minecraft:skeleton_skull', 'minecraft:knowledge_book'],
      Sam: ['minecraft:skeleton_skull', 'minecraft:compass'],
      Pat: ['minecraft:clock', 'minecraft:spyglass']
    }
    for (const [name, items] of Object.entries(expected)) {
      const cell = await cellOf(ctx, admin, 'season-2', cells, name)
      const { first, second } = trophies(cell)
      await ctx.eventually(async () => {
        assert(await shownItem(ctx, admin, { trophy: first }) === items[0], `${name}'s first trophy is ${items[0]}`)
        assert(await shownItem(ctx, admin, { trophy: second }) === items[1], `${name}'s second trophy is ${items[1]}`)
      }, 10 * SECOND)
    }
    const admins = await cellOf(ctx, admin, 'season-2', cells, 'SeasonsAdmin')
    assert(await shownItem(ctx, admin, { trophy: trophies(admins).first }) === null, 'no trophy for an admin who won nothing')
    const pats = await cellOf(ctx, admin, 'season-2', cells, 'Pat')
    const plaque = await plaqueText(ctx, admin, { plaque: pats.plaque })
    assert(/Most dedicated · Furthest builder/.test(plaque), `Pat's plaque names the awards: ${plaque}`)

    // The museum's plaque for Season 2: no slayer, so no egg, but every winner.
    await ctx.eventually(async () => {
      const museum = await plaqueText(ctx, admin, PLINTHS.eggS2)
      assert(/Season 2/.test(museum ?? '') && /Most deaths: Alex and Sam/.test(museum) && /Furthest builder: Pat/.test(museum), `the museum lists the winners: ${museum}`)
    }, 10 * SECOND)
    assert(await shownItem(ctx, admin, PLINTHS.eggS2) === null, 'no egg without a slayer')

    // The season card and /season firsts.
    const card = await ctx.command(admin, '/scenario card Pat 2', /^Card of Pat for Season 2: /)
    for (const line of [' | Awards | ', ' | Most dedicated (', ' | Furthest builder (3,000 blocks)']) {
      assert(card.includes(line), `Pat's card has "${line}": ${card}`)
    }
    await ctx.command(admin, '/season firsts', [/^Season 2 awards:$/, /^Most deaths: Alex and Sam \(5\)$/])
  }
}
