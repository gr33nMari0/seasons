// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A63. Tab completion offers online and known player names, gallery and cell ids, templates,
// candidates, links, zones, config paths and each argument's choices, and nothing for commands the
// player may not run (questions 266).
import { assert } from '../lib/context.mjs'
import { suggestions, suggests } from '../lib/commands.mjs'
import { HOME, hallToVisit, standAt } from '../lib/visits.mjs'

export default {
  name: 'a63-tab-complete',
  async run (ctx) {
    const admin = await ctx.admin()
    const visitor = await ctx.join('Visitor')
    await hallToVisit(ctx, admin, { sealed: null })
    await standAt(ctx, admin, ctx.adminName, HOME)
    await ctx.command(admin, '/season spawn candidate add north', /^Added spawn candidate north /)
    await ctx.command(admin, '/hof link add home hall 100 -61 100', /^Link home at 100, -61, 100 leads /)
    await ctx.command(admin, '/season zone add base box 200 -64 200 210 -50 210', /^Added zone base: /)

    // Galleries, cells, players.
    await suggests(admin, '/hof gallery info ', ['season-2'])
    await suggests(admin, '/hof cell assign season-2:', ['season-2:c1', 'season-2:c2'])
    await suggests(admin, '/hof cell assign season-2:c1 ', [ctx.adminName, 'Visitor'])
    // A known player who is offline is offered where offline players are allowed.
    await ctx.leave(visitor)
    await suggests(admin, '/hof cell assign season-2:c1 Vis', ['Visitor'])
    await suggests(admin, '/season audit ', ['Visitor'])

    // Templates, candidates, links, zones and choices.
    await suggests(admin, '/hof stamp ', ['alcove', 'plinth'])
    await suggests(admin, '/season spawn set ', ['north', 'at'])
    await suggests(admin, '/hof link remove ', ['home'])
    await suggests(admin, '/season zone mode ', ['base'])
    await suggests(admin, '/season zone mode base ', ['off', 'announce', 'block', 'no-build'])
    await suggests(admin, '/hof cell gate season-2:c1 ', ['open', 'shut'])
    await suggests(admin, '/hof cell anchor season-2:c1 ', ['figure', 'archive', 'plaque', 'trophy', 'entry', 'gate'])

    // Positions: relative words and here.
    await suggests(admin, '/hof hall spawn ', ['~', 'here'])
    await suggests(admin, '/hof cell bounds season-2:c1 ', ['~', 'sel'])

    // Config paths, one level at a time, and never the secrets.
    await suggests(admin, '/season config get ho', ['hof'])
    const entry = await suggests(admin, '/season config set hof.entry.', ['hof.entry.requireBed', 'hof.entry.bedDistance'])
    assert(entry.every((s) => s.startsWith('hof.entry.')), `only hof.entry keys: ${entry}`)
    const top = await suggestions(admin, '/season config get ')
    assert(!top.some((s) => s.startsWith('secrets') || s.includes('webhook')), `no secrets: ${top}`)

    // A player without the admin nodes is offered none of their commands.
    const player = await ctx.join('Player')
    const hof = await suggestions(player, '/hof ')
    for (const admin of ['gallery', 'cell', 'stamp', 'wand', 'import']) {
      assert(!hof.includes(admin), `a player isn't offered /hof ${admin}: ${hof}`)
    }
    assert(hof.includes('leave'), `a player is offered /hof leave: ${hof}`)
    const cell = await suggestions(player, '/hof cell ')
    assert(!cell.some((s) => ['new', 'bounds', 'assign'].includes(s)), `a player is offered no cell commands: ${cell}`)
  }
}
