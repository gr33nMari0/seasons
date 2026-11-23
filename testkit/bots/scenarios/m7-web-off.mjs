// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Seasons Web is off by default: a fresh install writes web.yml with enabled: false and the
// default address 127.0.0.1:8019, nothing listens, the /web commands say the dashboard is off, and
// Seasons carries on as without it.
import { assert } from '../lib/context.mjs'
import { webBase } from '../lib/web.mjs'

export default {
  name: 'm7-web-off',
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    const yml = ctx.readFile('plugins/SeasonsWeb/web.yml')
    assert(/^enabled: false$/m.test(yml), `off by default:\n${yml}`)
    assert(/^bind: "?127\.0\.0\.1:8019"?$/m.test(yml), `the default address:\n${yml}`)
    assert(/^public: true$/m.test(yml), `public once on:\n${yml}`)
    assert(/^#/m.test(yml), 'web.yml is commented')

    let refused = false
    try {
      await fetch(`${webBase(ctx)}/.well-known/seasons.json`)
    } catch {
      refused = true
    }
    assert(refused, 'nothing listens on the web port')

    for (const command of ['/web link ABCD-EFGH', '/web sessions', '/web logout all', '/web privacy']) {
      await ctx.command(alex, command, /^The Seasons dashboard is off on this server\.$/)
    }
    await ctx.command(admin, '/season info', /Seasons/)
    assert(!/SeasonsWeb.*(WARN|ERROR)|(WARN|ERROR).*SeasonsWeb/.test(ctx.serverLog()), 'Seasons Web logged no problem')
  }
}
