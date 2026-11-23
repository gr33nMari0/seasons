// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Rate limits per visitor and per account: past the limit a request is refused with 429 and
// Retry-After, and allowed again once the minute has passed.
import { MINUTE, assert } from '../lib/context.mjs'
import { WebClient, login, webConfig } from '../lib/web.mjs'

export default {
  name: 'm7-rate-limits',
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    await webConfig(ctx, admin, 'proxy:\n  trusted: [127.0.0.1]\nrate-limits:\n  requests-per-minute-per-ip: 10\n  requests-per-minute-per-account: 15\n')

    const visitor = new WebClient(ctx, { headers: { 'CF-Connecting-IP': '203.0.113.50' } })
    for (let i = 1; i <= 10; i++) {
      assert((await visitor.get('/api/v1/season')).status === 200, `request ${i} is allowed`)
    }
    const over = await visitor.get('/api/v1/season')
    assert(over.status === 429 && over.json.error === 'rate-limited', `the 11th is refused: ${over.status}`)
    assert(Number(over.headers.get('retry-after')) > 0, `with Retry-After: ${over.headers.get('retry-after')}`)
    assert(over.headers.get('cache-control') === 'private, no-store', 'a refusal is never cached')

    // An account's limit holds across addresses.
    const roaming = new WebClient(ctx, { headers: { 'CF-Connecting-IP': '203.0.113.60' } })
    await login(ctx, alex, roaming)
    let refused = 0
    for (let i = 0; i < 16; i++) {
      roaming.headers['CF-Connecting-IP'] = `198.51.100.${i + 1}`
      if ((await roaming.get('/api/v1/season')).status === 429) refused++
    }
    assert(refused >= 1, `Alex's account is limited whatever the address: ${refused} refused`)

    await ctx.advance(admin, MINUTE + 1000)
    assert((await visitor.get('/api/v1/season')).status === 200, 'allowed again after a minute')
    assert((await roaming.get('/api/v1/season')).status === 200, 'Alex too')
  }
}
