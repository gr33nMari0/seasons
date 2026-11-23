// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Every response carries a strict content security policy: no third-party scripts, frames only
// from the map's address and open.spotify.com, and the dashboard itself can't be framed. Link
// URLs must be https; one that isn't is left out with a warning, and labels are plain text.
import { assert } from '../lib/context.mjs'
import { WebClient, webConfig } from '../lib/web.mjs'

const LINKS = [
  'links:',
  '  - { label: Server playlist, url: "https://open.spotify.com/playlist/abc", embed: spotify }',
  '  - { label: "<b>Map</b>", url: "https://map.example.com", embed: frame }',
  '  - { label: Discord, url: "https://discord.gg/example" }',
  '  - { label: Old map, url: "http://map.example.com:25572" }',
  ''
].join('\n')

export default {
  name: 'm7-security-headers',
  async run (ctx) {
    const admin = await ctx.admin()
    await webConfig(ctx, admin, `public-url: https://seasons.example.com\n${LINKS}`)
    assert(/Old map.*https|https.*Old map/.test(ctx.serverLog()), 'the http link is reported')
    const client = new WebClient(ctx)
    for (const path of ['/', '/api/v1/season', '/api/v1/nothing', '/.well-known/seasons.json']) {
      const res = await client.get(path)
      const csp = res.headers.get('content-security-policy') ?? ''
      const rules = Object.fromEntries(csp.split(';').map((r) => r.trim()).filter(Boolean).map((r) => {
        const [name, ...values] = r.split(/\s+/)
        return [name, values.join(' ')]
      }))
      assert(rules['default-src'] === "'self'", `${path}: default-src 'self': ${csp}`)
      assert(rules['script-src'] === "'self'", `${path}: no third-party scripts: ${csp}`)
      assert(rules['frame-ancestors'] === "'none'", `${path}: can't be framed: ${csp}`)
      assert(rules['frame-src'] === 'https://map.example.com https://open.spotify.com', `${path}: frames from the map and Spotify only: ${csp}`)
      assert(rules['object-src'] === "'none'" && rules['base-uri'] === "'none'", `${path}: no plugins or base: ${csp}`)
      assert(/textures\.minecraft\.net/.test(rules['img-src'] ?? ''), `${path}: skins from Mojang: ${csp}`)
      assert(res.headers.get('x-frame-options') === 'DENY', `${path}: X-Frame-Options`)
      assert(res.headers.get('x-content-type-options') === 'nosniff', `${path}: nosniff`)
      assert(res.headers.get('referrer-policy') === 'no-referrer', `${path}: no referrer`)
      assert(/max-age=\d+/.test(res.headers.get('strict-transport-security') ?? ''), `${path}: HSTS with an https public-url`)
    }
    const links = (await client.get('/api/v1/links')).json.links
    assert(links.length === 3 && !links.some((l) => l.url.startsWith('http:')), `only https links: ${JSON.stringify(links)}`)
    assert(links.some((l) => l.label === '<b>Map</b>'), `labels are plain text, as written: ${JSON.stringify(links)}`)
    assert(links.find((l) => l.label === 'Server playlist').embed === 'spotify', 'the playlist embeds')
  }
}
