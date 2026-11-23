// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Device-code login: the dashboard shows an 8-character code, the player types /web link with it,
// and only the browser that asked for the code gets the session, as an HttpOnly, Secure,
// SameSite=Strict cookie. The token is never in a response body, and only its hash is stored.
// A code works once; wrong codes are limited per account, and logins and failures are audited.
import { createHash } from 'node:crypto'
import { assert } from '../lib/context.mjs'
import { DEVICE_COOKIE, SESSION_COOKIE, WebClient, deviceCode, webConfig } from '../lib/web.mjs'

const CODE = /^[23456789ABCDEFGHJKMNPQRSTVWXYZ]{4}-[23456789ABCDEFGHJKMNPQRSTVWXYZ]{4}$/
const WEB_DATA = 'world/dimensions/minecraft/overworld/seasons-web'
const INVALID = /^That code isn't valid or has expired\. Show a new code on the dashboard\.$/

function assertCookie (line, name, what) {
  assert(line, `${what} is set`)
  const attributes = line.split(';').map((a) => a.trim().toLowerCase())
  for (const wanted of ['httponly', 'secure', 'samesite=strict', 'path=/']) {
    assert(attributes.includes(wanted), `${what} has ${wanted}: ${line}`)
  }
  assert(!attributes.some((a) => a.startsWith('domain=')), `${what} has no Domain: ${line}`)
  assert(line.startsWith(`${name}=`), `${what} is ${name}: ${line}`)
}

export default {
  name: 'm7-device-login',
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    const sam = await ctx.join('Sam')
    await webConfig(ctx, admin)

    const ipad = new WebClient(ctx)
    const asked = await deviceCode(ipad)
    assert(asked.status === 200, `a code: ${asked.status}`)
    assert(CODE.test(asked.json.code), `8 characters with no look-alikes: ${asked.json.code}`)
    assert(asked.json.expiresIn === 300 && asked.json.interval >= 1, `5 minutes: ${asked.text}`)
    assertCookie(ipad.setCookie(DEVICE_COOKIE), DEVICE_COOKIE, 'the device cookie')

    const pending = await ipad.get('/api/v1/auth/poll')
    assert(pending.status === 202 && pending.json.status === 'pending', `pending until linked: ${pending.status} ${pending.text}`)

    // Someone who only saw the code can't collect the session.
    const onlooker = new WebClient(ctx)
    const peek = await onlooker.get('/api/v1/auth/poll')
    assert(peek.status === 400, `no device cookie, no session: ${peek.status}`)

    // Typed in lower case without the dash, as players will.
    await ctx.command(alex, `/web link ${asked.json.code.replace('-', '').toLowerCase()}`, /^Linked: this device is now logged in as Alex\.$/)
    await new Promise((resolve) => setTimeout(resolve, (asked.json.interval ?? 2) * 1000))
    const linked = await ipad.get('/api/v1/auth/poll')
    assert(linked.status === 200 && linked.json.status === 'linked' && linked.json.player.name === 'Alex', `linked as Alex: ${linked.text}`)
    const session = ipad.setCookie(SESSION_COOKIE)
    assertCookie(session, SESSION_COOKIE, 'the session cookie')
    assert(/max-age=2592000/i.test(session), `30 days: ${session}`)
    const token = ipad.cookies.get(SESSION_COOKIE)
    assert(token.length >= 40, `a long random token: ${token.length}`)
    assert(!linked.text.includes(token), 'the token is not in the body')
    assert(!ipad.cookies.has(DEVICE_COOKIE), 'the device cookie is cleared')
    assert(linked.headers.get('cache-control') === 'private, no-store', 'the login is never cached')

    const boards = await ipad.get('/api/v1/leaderboards')
    assert(boards.status === 200, `the session works: ${boards.status}`)

    await ctx.eventually(async () => {
      const stored = ctx.readFile(`${WEB_DATA}/sessions.json`)
      assert(!stored.includes(token), 'the token is not stored')
      assert(stored.includes(createHash('sha256').update(token).digest('hex')), 'its hash is')
    })

    // A code works once.
    await ctx.command(sam, `/web link ${asked.json.code}`, INVALID)

    // Wrong codes: five a minute per account (the used code above was the first), then refused
    // whatever is typed.
    const fresh = await deviceCode(new WebClient(ctx))
    for (let i = 0; i < 4; i++) {
      await ctx.command(sam, '/web link ZZZZ-ZZZZ', INVALID)
    }
    await ctx.command(sam, `/web link ${fresh.json.code}`, /^Too many wrong codes\. Try again in a minute\.$/)

    await ctx.eventually(async () => {
      const month = new Date().toISOString().slice(0, 7)
      const audit = ctx.readFile(`${WEB_DATA}/audit/${month}.jsonl`).trim().split('\n').map((l) => JSON.parse(l))
      assert(audit.some((e) => e.action === 'web.login' && e.actor === 'Alex' && e.result === 'ok'), 'the login is audited')
      assert(audit.filter((e) => e.action === 'web.link-failed' && e.actor === 'Sam').length >= 5, 'failed codes are audited')
      assert(audit.some((e) => e.action === 'web.link-locked' && e.actor === 'Sam'), 'the lockout is audited')
      assert(!JSON.stringify(audit).includes(token), 'no token in the audit log')
      assert(!/127\.0\.0\.1/.test(JSON.stringify(audit)), 'no address in the audit log')
    })
  }
}
