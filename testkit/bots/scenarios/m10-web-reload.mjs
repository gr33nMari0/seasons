// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// /web reload (M10 plan, O1): an op changes web.yml's name and a link and reloads it from the game;
// the reply names what changed, the dashboard shows the new name at once, and a logged-in device
// stays logged in. A web.yml that doesn't check out keeps the dashboard running as before and
// lists the problems; a player without seasons.web.admin.reload doesn't have the command.
import { assert } from '../lib/context.mjs'
import { WebClient, login, webConfig } from '../lib/web.mjs'

export default {
  name: 'm10-web-reload',
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    const base = `enabled: true\nbind: 127.0.0.1:${ctx.webPort}\n`
    await webConfig(ctx, admin, 'name: Example SMP\n')

    const device = new WebClient(ctx)
    await login(ctx, alex, device)
    const before = await device.get('/api/v1/dashboard')
    assert(before.json.name === 'Example SMP', `the name before: ${before.json.name}`)

    ctx.writeFile('plugins/SeasonsWeb/web.yml', `schema-version: 1\n${base}name: Example Reloaded\n` +
      'links:\n  - { label: Discord, url: "https://discord.gg/example" }\n')
    const [, changed] = await ctx.command(admin, '/web reload',
      [/^Seasons Web reloaded: listening on 127\.0\.0\.1:\d+\.$/, /^Changed: /])
    assert(/name/.test(changed) && /links/.test(changed), `the reply names what changed: ${changed}`)
    assert(!/bind/.test(changed), `bind didn't change: ${changed}`)
    const after = await device.get('/api/v1/dashboard')
    assert(after.status === 200 && after.json.name === 'Example Reloaded', `the new name: ${after.json.name}`)
    assert(after.json.viewer.kind === 'player', 'the device is still logged in')

    await ctx.command(admin, '/web reload', [/^Seasons Web reloaded: listening on /, /^Nothing changed in web\.yml\.$/])

    // A broken web.yml: the dashboard keeps running as it was.
    ctx.writeFile('plugins/SeasonsWeb/web.yml', `schema-version: 1\nenabled: true\nbind: nowhere\nname: Broken\n`)
    await ctx.command(admin, '/web reload',
      [/^web\.yml has a problem, so the dashboard keeps running as it was:$/, /^- bind: /])
    const still = await device.get('/api/v1/dashboard')
    assert(still.status === 200 && still.json.name === 'Example Reloaded', `the old settings still serve: ${still.json.name}`)

    // Turning it off on purpose is applied.
    ctx.writeFile('plugins/SeasonsWeb/web.yml', 'schema-version: 1\nenabled: false\n')
    await ctx.command(admin, '/web reload', /^Seasons Web reloaded: off \(enabled: false\)\.$/)
    const off = await device.get('/api/v1/dashboard').catch(() => ({ status: 0 }))
    assert(off.status === 0, 'nothing listens once it is off')

    await ctx.command(alex, '/web reload', /Unknown or incomplete command|You do not have permission/i)
  }
}
