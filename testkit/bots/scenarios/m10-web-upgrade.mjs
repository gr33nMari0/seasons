// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// /web upgrade (M10 plan, O3): a web.yml written by an older Seasons Web, with the admin's own
// notes and values, lacks settings this version has. At the next start the log says
// so; --dry-run lists them and changes nothing; /web upgrade keeps a backup, adds each
// missing setting with its default and its explanation, and leaves every line the admin wrote as
// it was; /web check then has nothing missing to report, and a second upgrade has nothing to add.
import { assert } from '../lib/context.mjs'

const OLD = `# Our dashboard. Ask Alex before changing anything.
schema-version: 1
enabled: true
bind: 127.0.0.1:PORT
name: Example SMP   # shown on the connect page
public: true
proxy:
  trusted: [127.0.0.1]
sessions:
  lifetime: 14d
`

export default {
  name: 'm10-web-upgrade',
  sessions: [
    async (ctx) => {
      const admin = await ctx.admin()
      ctx.writeFile('plugins/SeasonsWeb/web.yml', OLD.replace('PORT', ctx.webPort))
      await ctx.command(admin, '/web reload', /^Seasons Web reloaded: listening on /)
    },
    async (ctx) => {
      // The restart's own log, from before this session began.
      assert(/web\.yml lacks \d+ settings this version has; run \/web upgrade to add them/.test(ctx.readFile('logs/latest.log')),
        'the startup log says settings are missing')
      const admin = await ctx.admin()
      const written = OLD.replace('PORT', ctx.webPort)

      await ctx.command(admin, '/web upgrade --dry-run', [
        /^web\.yml lacks \d+ settings: .*previews/,
        /^Run \/web upgrade to add them with their defaults; a backup of web\.yml is kept\.$/
      ])
      assert(ctx.readFile('plugins/SeasonsWeb/web.yml') === written, 'the dry run changes nothing')

      const [added, backup] = await ctx.command(admin, '/web upgrade', [
        /^Added \d+ settings to web\.yml: /,
        /^The file as it was is web\.yml\.bak-[0-9A-Za-z.-]+; run \/web reload to apply the new settings\.$/
      ])
      assert(/proxy\.client-ip-header/.test(added) && /previews/.test(added) && /game-addresses/.test(added),
        `the missing settings, nested ones too: ${added}`)
      const name = backup.match(/(web\.yml\.bak-[0-9A-Za-z.-]+);/)[1]
      assert(ctx.readFile(`plugins/SeasonsWeb/${name}`) === written, 'the backup is the file as it was')

      const now = ctx.readFile('plugins/SeasonsWeb/web.yml')
      for (const line of written.trimEnd().split('\n')) {
        assert(now.includes(line), `the admin's line is kept: ${line}`)
      }
      assert(/^  client-ip-header: CF-Connecting-IP$/m.test(now), 'the nested setting goes under proxy:')
      assert(/^previews:\n  enabled: true\n  skins: true$/m.test(now), 'a whole missing section, as bundled')
      assert(/# Rich previews when a dashboard link is shared/.test(now), 'with its explanation')
      assert(/^  lifetime: 14d$/m.test(now), 'the admin\'s values are kept')

      await ctx.command(admin, '/web reload', /^Seasons Web reloaded: listening on /)
      const seen = []
      const record = (text) => seen.push(text)
      admin.on('messagestr', record)
      await ctx.command(admin, '/web check', /^Check done: /, 30_000)
      admin.off('messagestr', record)
      assert(!seen.some((t) => /lacks/.test(t)), `nothing missing any more: ${seen.join(' | ')}`)
      await ctx.command(admin, '/web upgrade', /^web\.yml already has every setting\.$/)
    }
  ]
}
