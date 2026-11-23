// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Second-screen mode in WebKit, the engine of every iPad browser, at an iPad's landscape size: the
// widgets web.yml lists, in its order (an unknown name is a warning at load and left out), in a dark
// theme with large type and nothing to scroll; it keeps itself up to date with no reload, through a
// join, a leave and Seasons Web restarting; it holds the screen awake and asks again when it comes
// back into view; without the Wake Lock API it says so and carries on; held upright it asks to be
// turned.
import { MINUTE, SECOND, assert, sleep } from '../lib/context.mjs'
import { IPAD_LANDSCAPE, dashboard, openPage } from '../lib/browser.mjs'

export default {
  name: 'm7-second-screen',
  timeoutMs: 5 * MINUTE,
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Alex')
    const { origin } = await dashboard(ctx, admin, [
      'links:',
      '  - { label: Discord, url: "https://discord.gg/example" }',
      'screens:',
      '  second-screen:',
      '    layout: landscape',
      '    widgets: [event-feed, season-clock, online, bogus, leaderboard, links]',
      ''
    ].join('\n'))
    assert(/web\.yml screens\.second-screen: no widget "bogus"/.test(ctx.serverLog()), 'an unknown widget is a warning at load')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)

    const page = await openPage(ctx, origin, '/screen', { engine: 'webkit', viewport: IPAD_LANDSCAPE, wakeLock: 'stub' })
    await page.waitForWidget('season', /Season 2/)
    await page.page.evaluate(() => { window.__notReloaded = true })
    const widgets = await page.widgets()
    // Leaderboards need a login, which this screen hasn't.
    assert(JSON.stringify(widgets) === '["event-feed","season","online","links"]', `web.yml's widgets in order: ${JSON.stringify(widgets)}`)
    const look = await page.page.evaluate(() => {
      const bg = getComputedStyle(document.body).backgroundColor.match(/\d+(\.\d+)?/g).map(Number)
      const luminance = (0.2126 * bg[0] + 0.7152 * bg[1] + 0.0722 * bg[2]) / 255
      return {
        luminance,
        fontSize: parseFloat(getComputedStyle(document.body).fontSize),
        scrolls: document.scrollingElement.scrollHeight > window.innerHeight + 1 || document.scrollingElement.scrollWidth > window.innerWidth + 1
      }
    })
    assert(look.luminance < 0.2, `a dark theme: ${JSON.stringify(look)}`)
    assert(look.fontSize >= 20, `large type: ${JSON.stringify(look)}`)
    assert(!look.scrolls, `nothing to scroll: ${JSON.stringify(look)}`)

    // The screen is kept awake, and asked for again when the page comes back into view.
    await ctx.eventually(async () => assert(await page.page.evaluate(() => window.__wake.requests) === 1, 'the wake lock asked for on load'))
    await page.page.waitForSelector('[data-wake="on"]', { timeout: 10 * SECOND })
    await page.page.evaluate(() => {
      window.__wake.sentinel.release()
      Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' })
      document.dispatchEvent(new Event('visibilitychange'))
      Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' })
      document.dispatchEvent(new Event('visibilitychange'))
    })
    await ctx.eventually(async () => assert(await page.page.evaluate(() => window.__wake.requests) === 2, 'asked for again once visible'))

    // It keeps itself up to date.
    const sam = await ctx.join('Sam')
    await page.waitForWidget('event-feed', /Sam joined/)
    await page.waitForWidget('online', /Sam/, 45 * SECOND)
    await ctx.leave(sam)
    await ctx.eventually(async () => assert(!/Sam/.test(await page.widget('online')), 'Sam has left'), 45 * SECOND)
    const streams = () => page.apiPaths().filter((p) => p === '/api/v1/events').length
    const opened = streams()
    await ctx.command(admin, '/scenario web reload', /^Web reloaded: listening on /)
    await ctx.eventually(async () => assert(streams() > opened, 'the feed opened again'), 30 * SECOND)
    await sleep(SECOND)
    await ctx.join('Taylor')
    await page.waitForWidget('event-feed', /Taylor joined/, 45 * SECOND)
    assert(await page.page.evaluate(() => window.__notReloaded === true), 'all without a reload')
    page.check('second-screen mode')
    await page.close()

    // No Wake Lock API, as on an iPad before iPadOS 16.4: it says so and works.
    const old = await openPage(ctx, origin, '/screen', { engine: 'webkit', viewport: IPAD_LANDSCAPE, wakeLock: 'none' })
    await old.waitForWidget('season', /Season 2/)
    await old.page.waitForSelector('[data-wake="unsupported"]', { timeout: 10 * SECOND })
    old.check('second-screen mode without the Wake Lock API')
    await old.close()

    // Upright, it asks to be turned and still shows its widgets.
    const upright = await openPage(ctx, origin, '/screen', { engine: 'webkit', viewport: { width: IPAD_LANDSCAPE.height, height: IPAD_LANDSCAPE.width }, wakeLock: 'stub' })
    await upright.waitForWidget('season', /Season 2/)
    assert(await upright.page.locator('[data-notice="rotate"]').isVisible(), 'it asks to be turned')
    upright.check('second-screen mode held upright')
  }
}
