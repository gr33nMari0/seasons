// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The dashboard keeps the spec's security rules on every page: no CSP violation, no script error and
// no request beyond its own address, Mojang's skin server and the frames allowed, logged in or not;
// its files carry ETags and answer 304 when unchanged, with the right types and nosniff; nothing but
// the page's own files is served.
import { MINUTE, assert } from '../lib/context.mjs'
import { buildGallery } from '../lib/hall.mjs'
import { dashboard, loginOnPage, openPage } from '../lib/browser.mjs'
import { WebClient, uuid } from '../lib/web.mjs'

const ROUTES = (alex) => ['/', `/player/${alex}`, '/hall/2', '/museum', '/history', '/screen']

export default {
  name: 'm7-dashboard-csp',
  timeoutMs: 4 * MINUTE,
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    const { origin } = await dashboard(ctx, admin)
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await buildGallery(ctx, admin, { cells: 2 })
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)

    const page = await openPage(ctx, origin, '/')
    for (const route of ROUTES(uuid('Alex'))) {
      await page.goto(route)
      page.check(`${route} for a visitor`)
    }
    await loginOnPage(ctx, page, alex)
    for (const route of ROUTES(uuid('Alex'))) {
      await page.goto(route)
      page.check(`${route} logged in`)
    }

    // The page's files: types, ETags, 304s; nothing else.
    const client = new WebClient(ctx)
    const index = await client.get('/')
    assert(index.status === 200 && /^text\/html/.test(index.headers.get('content-type')), `the page: ${index.status}`)
    assert(!/<script>|\bstyle="|\bon[a-z]+=/.test(index.text), 'no inline script, style or handler in the page')
    const scripts = [...index.text.matchAll(/<script[^>]*src="([^"]+)"/g)].map((m) => m[1])
    assert(scripts.length > 0 && scripts.every((s) => s.startsWith('/app/')), `scripts only from /app/: ${scripts}`)
    const app = await client.get(scripts[0])
    assert(app.status === 200 && /^text\/javascript/.test(app.headers.get('content-type')), `a script's type: ${app.headers.get('content-type')}`)
    assert(app.headers.get('x-content-type-options') === 'nosniff', 'nosniff')
    const etag = app.headers.get('etag')
    assert(etag && /^"[0-9a-f]+"$/.test(etag), `a strong ETag: ${etag}`)
    assert(app.headers.get('cache-control') === 'no-cache', `checked every time: ${app.headers.get('cache-control')}`)
    const again = await client.get(scripts[0], { headers: { 'If-None-Match': etag } })
    assert(again.status === 304 && again.text === '', `304 when unchanged: ${again.status}`)
    const css = await client.get('/app/style.css')
    assert(css.status === 200 && /^text\/css/.test(css.headers.get('content-type')), 'the stylesheet')
    const icons = await client.get('/app/icons.svg')
    assert(icons.status === 200 && /^image\/svg\+xml/.test(icons.headers.get('content-type')), 'our icons')
    for (const path of ['/app/nothing.mjs', '/app/../web.yml', '/app/%2e%2e/messages.yml', '/app/', '/web.yml', '/app/dashboard/app.mjs']) {
      const res = await client.get(path)
      assert(res.status === 404, `${path} is not served: ${res.status}`)
    }
  }
}
