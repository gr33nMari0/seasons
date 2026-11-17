// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A40. A broken YAML edit followed by /season reload leaves the previous config live and reports
// the file, line and message.
export default {
  name: 'a40-reload',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)

    ctx.writeFile('plugins/Seasons/config.yml', 'schema-version: 1\nschedule:\n  endCountdown: 2m\n')
    await ctx.command(admin, '/season reload', /^Reloaded /)

    // An unclosed list on line 4.
    ctx.writeFile('plugins/Seasons/config.yml', 'schema-version: 1\nschedule:\n  endCountdown: 3m\n  reminders: [7d, 1d\n')
    await ctx.command(admin, '/season reload', [
      /^Reload failed\. The previous config stays live\.$/,
      /^config\.yml, line \d+, column \d+: \S.*/
    ])
    await ctx.command(admin, '/season validate', /^config\.yml, line \d+, column \d+: \S.*/)

    // The previous config is still live: a 2-minute countdown, not 3 minutes or the default 10.
    await ctx.command(admin, '/season end', /^Season 2 ends in 2 minutes\b/)
    await ctx.waitForBossBar(admin, /^Season 2 ends in (2:00|1:[0-5]\d)$/)
    await ctx.command(admin, '/season cancel', /^The end of Season 2 was cancelled\b/)
  }
}
