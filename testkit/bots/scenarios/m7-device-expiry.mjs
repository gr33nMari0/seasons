// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A device code expires 5 minutes after it is shown: /web link refuses it and the dashboard's poll
// says it has expired.
import { MINUTE, SECOND, assert } from '../lib/context.mjs'
import { WebClient, deviceCode, webConfig } from '../lib/web.mjs'

export default {
  name: 'm7-device-expiry',
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    await webConfig(ctx, admin)
    const ipad = new WebClient(ctx)
    const early = await deviceCode(ipad)
    await ctx.advance(admin, 4 * MINUTE + 50 * SECOND)
    await ctx.command(alex, `/web link ${early.json.code}`, /^Linked: /)

    const late = new WebClient(ctx)
    const asked = await deviceCode(late)
    await ctx.advance(admin, 5 * MINUTE + SECOND)
    await ctx.command(alex, `/web link ${asked.json.code}`, /^That code isn't valid or has expired\. /)
    const polled = await late.get('/api/v1/auth/poll')
    assert(polled.status === 410 && polled.json.status === 'expired', `expired: ${polled.status} ${polled.text}`)
  }
}
