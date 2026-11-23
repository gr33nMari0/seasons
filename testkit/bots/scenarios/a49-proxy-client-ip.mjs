// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A49. Behind the tunnel, per-IP rate limits use CF-Connecting-IP; the same header sent from an
// address outside proxy.trusted is ignored.
// Device codes are limited to 5 a minute per visitor: behind a trusted proxy each visitor named
// by the header has their own 5; from an untrusted address every request counts against that
// address, whatever the header says.
import { assert } from '../lib/context.mjs'
import { WebClient, deviceCode, webConfig } from '../lib/web.mjs'

async function ask (ctx, ip) {
  const client = new WebClient(ctx, { headers: { 'CF-Connecting-IP': ip } })
  return deviceCode(client)
}

export default {
  name: 'a49-proxy-client-ip',
  async run (ctx) {
    const admin = await ctx.admin()
    await webConfig(ctx, admin, 'proxy:\n  trusted: [127.0.0.1]\n  client-ip-header: CF-Connecting-IP\n')

    // Trusted: each visitor named by the header has their own limit.
    for (let i = 1; i <= 5; i++) {
      const res = await ask(ctx, '203.0.113.7')
      assert(res.status === 200, `code ${i} for 203.0.113.7: ${res.status}`)
    }
    const sixth = await ask(ctx, '203.0.113.7')
    assert(sixth.status === 429, `the sixth code in a minute for 203.0.113.7 is refused: ${sixth.status}`)
    assert(/^\d+$/.test(sixth.headers.get('retry-after') ?? ''), `with Retry-After: ${sixth.headers.get('retry-after')}`)
    const other = await ask(ctx, '198.51.100.20')
    assert(other.status === 200, `another visitor behind the tunnel still gets a code: ${other.status}`)

    // Untrusted: the header is ignored, so every request counts against 127.0.0.1.
    await webConfig(ctx, admin, 'proxy:\n  trusted: [192.0.2.1]\n  client-ip-header: CF-Connecting-IP\n')
    for (let i = 1; i <= 5; i++) {
      const res = await ask(ctx, `198.51.100.${100 + i}`)
      assert(res.status === 200, `code ${i} from the untrusted address: ${res.status}`)
    }
    const blocked = await ask(ctx, '198.51.100.200')
    assert(blocked.status === 429, `a sixth code from the same address is refused whatever the header says: ${blocked.status}`)
  }
}
