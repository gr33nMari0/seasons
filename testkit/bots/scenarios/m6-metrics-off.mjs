// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// bStats on scenario servers: Seasons starts its metrics as on any server, but the shard turns
// bStats off for every plugin before the server boots, so no scenario server ever reports to
// bstats.org (docs/questions.md 290). Seasons Web, ViaVersion and BlueMap use the same switch.
import { assert } from '../lib/context.mjs'

export default {
  name: 'm6-metrics-off',
  async run (ctx) {
    const admin = await ctx.admin()
    const config = ctx.readFile('plugins/bStats/config.yml')
    assert(/^enabled: false$/m.test(config), `bStats is off for every plugin:\n${config}`)
    assert(/^serverUuid: [0-9a-f-]{36}$/m.test(config), `bStats keeps a server id, as it writes one:\n${config}`)
    await ctx.command(admin, '/version Seasons', /Seasons/)
    // Seasons started, metrics and all, without a word about bStats.
    assert(!/bStats/i.test(ctx.serverLog().split('\n').filter((l) => /Seasons/.test(l) && /(WARN|ERROR)/.test(l)).join('\n')), 'Seasons logged no bStats problem')
  }
}
