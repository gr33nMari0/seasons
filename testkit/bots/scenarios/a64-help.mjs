// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A64. /hof help, help cell, help cell row and help cell bounds show each usage with its arguments' types and
// choices; /hof cell alone lists its subcommands; a mistyped command shows its usage; after
// /hof gallery create, /hof cell new and /season end the admin is privately offered the next
// steps, and with admin.hints: false isn't; console help is plain text (questions 267 and 268).
import { asConsole } from '../lib/commands.mjs'
import { hallToVisit } from '../lib/visits.mjs'

export default {
  name: 'a64-help',
  async run (ctx) {
    const admin = await ctx.admin()
    const visitor = await ctx.join('Visitor')
    await hallToVisit(ctx, admin, { sealed: null })

    // Help for a group lists every command in it, each argument with what it takes.
    await ctx.command(admin, '/hof help cell', [
      /^Seasons help: \/hof cell$/,
      /^\/hof cell bounds <cell: [^>]+> \[<corners: [^>]+>\] - \S/,
      /^\/hof cell gate <cell: [^>]+> <state: \(open\|shut\)> - \S/,
      /^\/hof cell anchor <cell: [^>]+> <type: \(figure\|archive\|plaque\|trophy\|entry\|gate\)> \[<at: [^>]+>\] - \S/,
      /^\/hof cell assign <cell: [^>]+> <player: [^>]+> \[<season: [^>]+>\] - \S/
    ])
    await ctx.command(admin, '/hof help cell row', [/^Seasons help: \/hof cell row$/, /^\/hof cell row <gallery: gallery id> <count: 1 to 64> \[<options: --along \+x\|-x\|\+z\|-z, --wall 0 to 4, --across \+x\|-x\|\+z\|-z width, --like cell, --from corners>\] - \S/])
    await ctx.command(admin, '/hof help cell bounds', [/^Seasons help: \/hof cell bounds$/, /^\/hof cell bounds <cell: /])
    await ctx.command(admin, '/hof help', [/^Seasons help: \/hof$/, /^\/hof gallery create <gallery: [^>]+> <season: [^>]+>/, /^\/hof leave - /])
    await ctx.command(admin, '/season help', [/^Seasons help: \/season$/, /^\/season end \[<options: [^>]+>\] - /, /^\/season spawn set /])

    // Part of a command on its own lists what can follow; a wrong word says so first.
    await ctx.command(admin, '/hof cell', [/^Seasons help: \/hof cell$/, /^\/hof cell new <gallery: /])
    await ctx.command(admin, '/hof cell bogus', [/^There's no \/hof cell bogus\.$/, /^\/hof cell new <gallery: /])

    // A player sees only what they may run.
    const quiet = ctx.quiet(visitor, /^\/hof (gallery|cell|stamp|wand) /)
    await ctx.command(visitor, '/hof help', [/^Seasons help: \/hof$/, /^\/hof leave - /])
    await ctx.sync(admin, [visitor])
    quiet.verify()

    // Next steps, privately, after setup steps.
    await ctx.command(admin, '/hof gallery create season-3 3', [
      /^Created gallery season-3 for Season 3\.$/,
      /^Next: \/hof gallery bounds season-3, \/hof gallery entry season-3$/
    ])
    await ctx.command(admin, '/hof cell new season-2', [
      /^Created cell c3 in gallery season-2\.$/,
      /^Next: select the cell's corners with the wand, \/hof cell bounds season-2:c3, \/hof cell anchor season-2:c3 figure$/
    ])
    const told = ctx.quiet(visitor, /^Next: /)
    await ctx.command(admin, '/season end', [
      /^Season 2 ends in 10 minutes/,
      /^Next: \/season spawn candidate list, \/season spawn set, \/season launch --dry-run$/
    ])
    await ctx.command(admin, '/season cancel', /^The end of Season 2 was cancelled/)
    await ctx.sync(admin, [visitor])
    told.verify()

    // Turned off for everyone.
    await ctx.command(admin, '/season config set admin.hints false', /^Set admin\.hints to false in config\.yml/)
    const none = ctx.quiet(admin, /^Next: /)
    await ctx.command(admin, '/hof gallery create season-4 4', /^Created gallery season-4 for Season 4\.$/)
    await ctx.sync(admin)
    none.verify()

    // The console reads the same help as plain text.
    await asConsole(ctx, admin, 'hof help cell gate', /\/hof cell gate <cell: [^>]+> <state: \(open\|shut\)> - \S/)
  }
}
