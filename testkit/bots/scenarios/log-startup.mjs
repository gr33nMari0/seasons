// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The server log at startup (docs/questions.md 295): every Seasons line carries the [Seasons]
// label, never a Java class name, and a fresh start prints one short summary that says Seasons is
// idle until /season init and ends "Ready in". /season info tells an admin how to start, and
// /seasons is an alias of /season. Recording a season and reloading each log one line.
import { assert } from '../lib/context.mjs'
import { seasonsLines } from '../lib/log.mjs'

export default {
  name: 'log-startup',
  async run (ctx) {
    let admin = await ctx.admin()
    await ctx.command(admin, '/season info', /^No season is recorded yet\.$/)
    await ctx.command(admin, '/season info', /^Seasons is idle until an admin runs \/season init <season>\.$/)
    await ctx.command(admin, '/seasons info', /^No season is recorded yet\.$/)

    // A fresh start, as on a new server.
    const reply = await ctx.reset()
    assert(reply === 'ok', `the reset found a fresh boot: ${reply}`)
    admin = await ctx.admin()
    await ctx.eventually(async () => assert(seasonsLines(ctx).some((l) => /^Ready in \d+ ms/.test(l.text)), 'the summary ends'))

    const log = ctx.serverLog()
    assert(!/\[com\.petepeterepeat/.test(log), `no line is labelled with a class name:\n${log.split('\n').filter((l) => l.includes('[com.petepeterepeat')).join('\n')}`)
    const lines = seasonsLines(ctx)
    const first = lines.findIndex((l) => /^Seasons \S+ on /.test(l.text))
    const ready = lines.findIndex((l) => /^Ready in \d+ ms/.test(l.text))
    assert(first >= 0 && first < ready, `the summary starts with the version:\n${lines.map((l) => l.text).join('\n')}`)
    const summary = lines.slice(first, ready + 1).map((l) => l.text)
    assert(summary.length <= 10, `the summary is short (${summary.length} lines):\n${summary.join('\n')}`)
    const has = (pattern, what) => assert(summary.some((t) => pattern.test(t)), `the summary has ${what}:\n${summary.join('\n')}`)
    has(/Java \d+/, 'the Java version')
    has(/^Season: none recorded\. Seasons is idle until you run \/season init <season>\.$/, 'the idle season')
    has(/^Hall of Fame: not set up yet/, 'the Hall')
    has(/^Journal clean; 0 player records, 0 waiting to be archived$/, 'the journal and records')
    has(/^Discord: public webhook not set, admin webhook not set$/, 'the webhooks')
    has(/^BlueMap: /, 'the map')
    // A fresh install has no Hall, so its setup check finds something to do.
    assert(/^Ready in \d+ ms\. Setup check found \d+ problems?: run \/hof hall check$/.test(summary.at(-1)), `the last line points at /hof hall check: ${summary.at(-1)}`)

    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await ctx.eventually(async () => assert(seasonsLines(ctx).some((l) => l.level === 'INFO' && new RegExp(`^Season 2 recorded as the season in progress by ${admin.username}$`).test(l.text)), 'init is logged'))
    await ctx.command(admin, '/seasons reload', /^Reloaded /)
    await ctx.eventually(async () => assert(seasonsLines(ctx).some((l) => l.level === 'INFO' && new RegExp(`^Config reloaded by ${admin.username}`).test(l.text)), 'the reload is logged'))
  }
}
