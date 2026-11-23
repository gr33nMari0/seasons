// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Where Seasons Web may listen: localhost always; any other address only with an https public-url,
// or the dashboard doesn't start and says why. The port is the owner's choice, and a port already
// in use is reported rather than crashing.
import net from 'node:net'
import { assert } from '../lib/context.mjs'
import { WebClient, webBase, webConfig } from '../lib/web.mjs'

async function listening (ctx) {
  try {
    await fetch(`${webBase(ctx)}/.well-known/seasons.json`)
    return true
  } catch {
    return false
  }
}

export default {
  name: 'm7-web-bind',
  async run (ctx) {
    const admin = await ctx.admin()
    const port = ctx.webPort

    await webConfig(ctx, admin, `bind: 0.0.0.0:${port}\n`, { expect: /^Web reloaded: off \(.*public-url.*\)$/ })
    assert(!await listening(ctx), 'not listening beyond localhost without a public-url')
    assert(/Seasons Web.*public-url/i.test(ctx.serverLog()) || /public-url/.test(ctx.serverLog()), 'the log says why')

    await webConfig(ctx, admin, `bind: 0.0.0.0:${port}\npublic-url: http://seasons.example.com\n`, { expect: /^Web reloaded: off \(.*https.*\)$/ })
    assert(!await listening(ctx), 'not listening with an http public-url')

    await webConfig(ctx, admin, 'public-url: http://seasons.example.com\n', { expect: /^Web reloaded: off \(.*https.*\)$/ })
    assert(!await listening(ctx), 'a public-url must be https even on localhost')

    await webConfig(ctx, admin, 'public-url: https://seasons.example.com\n')
    const known = await new WebClient(ctx).get('/.well-known/seasons.json')
    assert(known.json.dashboard === 'https://seasons.example.com', `the public-url: ${known.text}`)

    await webConfig(ctx, admin, `bind: 0.0.0.0:${port}\npublic-url: https://seasons.example.com\n`)
    assert(await listening(ctx), 'listening on every address with an https public-url')

    // Another port, which the owner chooses; one in use is reported.
    const blocker = net.createServer()
    await new Promise((resolve) => blocker.listen(port + 1, '127.0.0.1', resolve))
    try {
      await webConfig(ctx, admin, `bind: 127.0.0.1:${port + 1}\n`, { expect: /^Web reloaded: off \(.*in use.*\)$/ })
    } finally {
      await new Promise((resolve) => blocker.close(resolve))
    }
    await webConfig(ctx, admin, `bind: 127.0.0.1:${port + 1}\n`, { expect: new RegExp(`^Web reloaded: listening on 127\\.0\\.0\\.1:${port + 1}$`) })
    const moved = await fetch(`http://127.0.0.1:${port + 1}/.well-known/seasons.json`)
    assert(moved.status === 200, `listening on the chosen port: ${moved.status}`)
    assert(!await listening(ctx), 'the old port is closed')
  }
}
