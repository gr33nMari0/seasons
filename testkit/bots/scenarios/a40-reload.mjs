// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A40. A broken YAML edit followed by /season reload leaves the previous config live and reports
// the file, line and message, to whoever ran it, to the other online admins and to the console.
import { SECOND, waitUntil } from '../lib/context.mjs'

const PROBLEM = /config\.yml, line \d+, column \d+: \S.*/

export default {
  name: 'a40-reload',
  async run (ctx) {
    const admin = await ctx.admin()
    const other = await ctx.join('Admin2')
    // ops.json outlives the wipe between runs, so Admin2 may already be an op.
    await ctx.command(admin, '/op Admin2', /Made Admin2 a server operator|already is an operator/)
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)

    ctx.writeFile('plugins/Seasons/config.yml', 'schema-version: 1\nschedule:\n  endCountdown: 2m\n')
    await ctx.command(admin, '/season reload', /^Reloaded /)

    // An unclosed list on line 4.
    ctx.writeFile('plugins/Seasons/config.yml', 'schema-version: 1\nschedule:\n  endCountdown: 3m\n  reminders: [7d, 1d\n')
    const toOther = ctx.waitForMessages(other, [
      new RegExp(`^${ctx.adminName} ran /season reload and it failed\\. The previous config stays live\\.$`),
      new RegExp(`^${PROBLEM.source}$`)
    ], 10 * SECOND, 'the failed reload, told to the other admin')
    await ctx.command(admin, '/season reload', [
      /^Reload failed\. The previous config stays live\.$/,
      new RegExp(`^${PROBLEM.source}$`)
    ])
    await toOther
    await waitUntil(() => PROBLEM.test(ctx.serverLog()), 10 * SECOND, 'the problem in the server log')
    await ctx.command(admin, '/season validate', new RegExp(`^${PROBLEM.source}$`))

    // The previous config is still live: a 2-minute countdown, not 3 minutes or the default 10.
    await ctx.command(admin, '/season end', /^Season 2 ends in 2 minutes\b/)
    await ctx.waitForBossBar(admin, /^Season 2 ends in (2:00|1:[0-5]\d)$/)
    await ctx.command(admin, '/season cancel', /^The end of Season 2 was cancelled\b/)
  }
}
