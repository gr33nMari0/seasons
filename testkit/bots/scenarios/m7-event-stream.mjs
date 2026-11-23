// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The event stream: joins, season firsts and entries into announce-mode zones reach every open
// stream; a keep-alive comment comes every 30 s so a proxy's idle timeout never cuts it; and each
// visitor may hold only a few streams open.
import { MINUTE, SECOND, assert, sleep } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { middle, move, standAt } from '../lib/visits.mjs'
import { box, launchFrontier } from '../lib/zones.mjs'
import { WebClient, login, waitForEvent, webConfig } from '../lib/web.mjs'

const visitorAt = (ctx, ip) => new WebClient(ctx, { headers: { 'CF-Connecting-IP': ip } })

export default {
  name: 'm7-event-stream',
  timeoutMs: 4 * MINUTE,
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    await webConfig(ctx, admin, 'proxy:\n  trusted: [127.0.0.1]\n')
    const phone = visitorAt(ctx, '203.0.113.1')
    await login(ctx, pat, phone)
    const logged = await phone.stream()
    assert(logged.status === 200, `a player's stream: ${logged.status}`)
    assert(logged.headers.get('cache-control') === 'private, no-store', 'a logged-in stream is private')
    const anon = await visitorAt(ctx, '203.0.113.2').stream()
    assert(anon.status === 200, `a visitor's stream: ${anon.status}`)
    const opened = Date.now()

    await ctx.join('Quinn')
    for (const stream of [logged, anon]) {
      await waitForEvent(stream, (e) => e.event === 'join' && e.data.player.name === 'Quinn', 15 * SECOND, 'a join')
    }

    await launchFrontier(ctx, admin)
    await ctx.command(admin, '/advancement grant Pat only minecraft:story/mine_stone', /^Granted the advancement/)
    const first = await waitForEvent(anon, (e) => e.event === 'first' && e.data.player.name === 'Pat', 15 * SECOND, 'a season first')
    assert(first.data.label === 'completed [Stone Age]' || /Stone Age/.test(first.data.label), `the first's label: ${JSON.stringify(first.data)}`)
    await waitForEvent(anon, (e) => e.event === 'phase', 15 * SECOND, 'the launch')

    await box(ctx, admin, 'market', { x: 300, y: FLOOR - 1, z: 300 }, { x: 320, y: FLOOR + 10, z: 320 })
    await ctx.command(admin, '/season zone mode market announce', /^Zone market is now announce while it is locked\.$/)
    await standAt(ctx, admin, 'Pat', { x: 297, y: FLOOR, z: 310 })
    await move(ctx, admin, 'Pat', middle({ x: 305, y: FLOOR, z: 310 }), 'walk')
    const zone = await waitForEvent(logged, (e) => e.event === 'zone', 15 * SECOND, 'a zone entry')
    assert(zone.data.player.name === 'Pat' && zone.data.zone === 'market', `the zone entry: ${JSON.stringify(zone.data)}`)
    assert(!/"[xyz]"\s*:/.test(JSON.stringify(logged.events)), 'no coordinates on the stream')

    // Keep-alives every 30 s.
    const wait = Math.max(0, opened + 32 * SECOND - Date.now())
    await sleep(wait)
    for (const stream of [logged, anon]) {
      assert(stream.comments.some((c) => c.text === 'keep-alive'), `a keep-alive within 30 s: ${JSON.stringify(stream.comments)}`)
      assert(!stream.ended, 'the stream is still open')
    }

    // Two streams per visitor at once.
    const busy = visitorAt(ctx, '203.0.113.3')
    const one = await busy.stream()
    const two = await busy.stream()
    const three = await busy.stream()
    assert(one.status === 200 && two.status === 200 && three.status === 429, `a third stream is refused: ${[one.status, two.status, three.status]}`)
    one.close()
    await ctx.eventually(async () => {
      const again = await busy.stream()
      assert(again.status === 200, `a closed stream frees its place: ${again.status}`)
    })
  }
}
