// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// /web check (M10 plan, O2): behind the suite's tunnel, the server fetches its own public-url and
// finds this dashboard there, sees the request arrive from the trusted proxy with its client-ip
// header, and reports an unknown setting with the one it probably meant, the settings web.yml
// lacks, and what isn't set up yet; nothing is applied. With the proxy not trusted it says per-IP
// limits would count everyone as one visitor; without a public-url it says the dashboard is only
// on this machine, and skips the network checks.
import { assert } from '../lib/context.mjs'
import { dashboard } from '../lib/browser.mjs'
import { webConfig } from '../lib/web.mjs'

const SUMMARY = /^Check done: \d+ passed, \d+ warnings?, \d+ problems?\.$/

export default {
  name: 'm10-web-check',
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    const tunnel = await dashboard(ctx, admin, 'name: Example SMP\npubic-url: https://typo.example.com\n')

    const lines = await ctx.command(admin, '/web check', [
      /^Seasons Web check:$/,
      /^Warn · Settings: web\.yml has an unknown setting pubic-url \(did you mean public-url\?\); it is ignored\.$/,
      /^Warn · Settings: web\.yml lacks \d+ settings? that this version has; \/web upgrade adds them\.$/,
      new RegExp(`^Pass · Address: ${tunnel.origin.replace(/[.]/g, '\\.')} answers as this dashboard\\.$`),
      /^Pass · Proxy: requests arrive from 127\.0\.0\.1, which proxy\.trusted lists, with CF-Connecting-IP\.$/,
      /^Warn · Discovery: no game-addresses in web\.yml, so players can't find the dashboard from the address they play on\.$/,
      /^Skip · Map: no link with embed: frame\.$/,
      SUMMARY
    ], 30_000)
    assert(lines.length === 8, 'every line came')
    assert(ctx.readFile('plugins/SeasonsWeb/web.yml').includes('pubic-url'), '/web check changes nothing')

    // The proxy isn't trusted: everyone behind it would be one visitor.
    await dashboard(ctx, admin, 'proxy:\n  trusted: []\n  client-ip-header: CF-Connecting-IP\n')
    await ctx.command(admin, '/web check', [
      /^Warn · Proxy: requests arrive from 127\.0\.0\.1 with CF-Connecting-IP, but proxy\.trusted doesn't list it, so per-visitor rate limits count everyone behind it as one visitor\.$/,
      SUMMARY
    ], 30_000)

    // No public-url: only this machine, and nothing to fetch.
    await webConfig(ctx, admin, '')
    await ctx.command(admin, '/web check', [
      /^Warn · Address: no public-url, so the dashboard is only reachable on this machine\.$/,
      /^Skip · Proxy: no public-url to check through\.$/,
      SUMMARY
    ], 30_000)

    await ctx.command(alex, '/web check', /Unknown or incomplete command|Incorrect argument for command|You do not have permission/i)
  }
}
