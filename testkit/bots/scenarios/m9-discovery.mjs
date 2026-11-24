// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Finding the dashboard from a game address (questions 336): the addresses in web.yml's
// game-addresses are served, normalised, in /.well-known/seasons.json for the connect page to
// confirm against; one that isn't a host name is left out with a warning; and /web discovery tells
// an admin the TXT record to publish for each, naming the public-url's origin.
import { assert } from '../lib/context.mjs'
import { WebClient, webConfig } from '../lib/web.mjs'

export default {
  name: 'm9-discovery',
  async run (ctx) {
    const admin = await ctx.admin()
    await webConfig(ctx, admin, [
      'public-url: https://seasons.example.com/',
      'game-addresses: [Play.Example.com, "play.example.com:25566", "play.example.com:25565", 203.0.113.7]',
      ''
    ].join('\n'))
    assert(/game-addresses: "203\.0\.113\.7" is left out/.test(ctx.serverLog()), 'the IP address is reported')

    const client = new WebClient(ctx)
    const res = await client.get('/.well-known/seasons.json')
    assert(res.status === 200, `well-known answered ${res.status}`)
    assert(res.headers.get('access-control-allow-origin') === '*', 'the connect page may read it')
    const body = res.json
    assert(JSON.stringify(body.gameAddresses) === JSON.stringify(['play.example.com', 'play.example.com:25566']),
      `game addresses normalised: ${JSON.stringify(body.gameAddresses)}`)

    await ctx.command(admin, '/web discovery',
      /^play\.example\.com: _seasons\.play\.example\.com\. TXT "v=seasons1; url=https:\/\/seasons\.example\.com"$/)
  }
}
