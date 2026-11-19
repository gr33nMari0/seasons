// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// /season config get and set. Get shows a setting in force, or every setting in a section. Set
// writes the value back into config.yml in place, so every comment and every other line stays as
// it was; it is in force at once and audited with the old value, the new one and who. A key the
// file lacks is added under its section. Each refusal: an unknown setting, a section, the schema
// version, a value of the wrong kind, one the rules refuse, and the same value again. With a
// broken hand edit not yet reloaded, set fails and config.yml is put back as it was. Webhook URLs
// are never shown or set, and one pasted into config.yml by mistake isn't shown either: the URL
// appears in no reply, audit entry or server log line. A player without seasons.admin.config can't
// use it.
import { assert } from '../lib/context.mjs'

const CONFIG = 'plugins/Seasons/config.yml'
const AUDIT = () => `world/dimensions/minecraft/overworld/seasons/audit/${new Date().toISOString().slice(0, 7)}.jsonl`
const SECRET = /^Webhook URLs live in secrets\.yml or the environment; Seasons never shows or sets them\. If you typed one here, it is now in the server log; rotate it\.$/

export default {
  name: 'm3-config',
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    const heard = []
    admin.on('messagestr', (text) => heard.push(text))
    pat.on('messagestr', (text) => heard.push(text))

    // Get: one setting, and a section.
    await ctx.command(admin, '/season config get hof.entry.cooldown', /^hof\.entry\.cooldown = 0s$/)
    await ctx.command(admin, '/season config get hof.entry', [/^hof\.entry:$/, /^hof\.entry\.bedDistance = 3$/, /^hof\.entry\.rateLimit = 5s$/])

    // Set: written in place, in force at once, audited.
    const bundled = ctx.readFile(CONFIG)
    await ctx.command(admin, '/season config set hof.entry.cooldown 30s', /^Set hof\.entry\.cooldown to 30s in config\.yml \(it was 0s\)\.$/)
    assert(ctx.readFile(CONFIG) === bundled.replace('cooldown: 0s', 'cooldown: 30s'), `only the value changed: ${ctx.readFile(CONFIG)}`)
    await ctx.command(admin, '/season config get hof.entry.cooldown', /^hof\.entry\.cooldown = 30s$/)
    await ctx.command(admin, '/season config set schedule.reminders [2d, 1h]', /^Set schedule\.reminders to \[2d, 1h\] in config\.yml \(it was \[7d, 1d, 1h, 10m\]\)\.$/)
    assert(ctx.readFile(CONFIG) === bundled.replace('cooldown: 0s', 'cooldown: 30s').replace('reminders: [7d, 1d, 1h, 10m]', 'reminders: [2d, 1h]'), `the list is written in place: ${ctx.readFile(CONFIG)}`)
    await ctx.command(admin, '/season config set hof.entry.cooldown 30s', /^hof\.entry\.cooldown is already 30s\.$/)

    // Refusals change nothing.
    const written = ctx.readFile(CONFIG)
    await ctx.command(admin, '/season config get hof.nope', /^There's no setting called hof\.nope\./)
    await ctx.command(admin, '/season config set hof.entry 5', /^hof\.entry is a section: set one of its keys, such as hof\.entry\.bedDistance\.$/)
    await ctx.command(admin, '/season config set schema-version 2', /^There's no setting called schema-version\./)
    await ctx.command(admin, '/season config set pets.maxPerPlayer lots', /^pets\.maxPerPlayer wasn't changed: /)
    await ctx.command(admin, '/season config set pets.maxPerPlayer 11', /^pets\.maxPerPlayer wasn't changed: config\.yml, at pets\.maxPerPlayer: must be between 0 and 10, not 11$/)
    await ctx.command(admin, '/season config set cells.access gate', /^cells\.access wasn't changed: config\.yml, at cells\.access: only open is built yet/)
    assert(ctx.readFile(CONFIG) === written, 'refusals leave config.yml alone')

    // A file that lacks the key gets it, under its section, which is made if it's missing.
    const trimmed = 'schema-version: 1\n# Our settings.\nschedule:\n  endCountdown: 2m   # short\n'
    ctx.writeFile(CONFIG, trimmed)
    await ctx.command(admin, '/season reload', /^Reloaded /)
    await ctx.command(admin, '/season config set schedule.autoEnd true', /^Set schedule\.autoEnd to true in config\.yml \(it was false\)\.$/)
    await ctx.command(admin, '/season config set pets.maxPerPlayer 3', /^Set pets\.maxPerPlayer to 3 in config\.yml \(it was 5\)\.$/)
    assert(ctx.readFile(CONFIG) === 'schema-version: 1\n# Our settings.\nschedule:\n  endCountdown: 2m   # short\n  autoEnd: true\npets:\n  maxPerPlayer: 3\n', `the keys are added: ${ctx.readFile(CONFIG)}`)
    await ctx.command(admin, '/season config get pets.maxPerPlayer', /^pets\.maxPerPlayer = 3$/)

    // A broken hand edit not yet reloaded: set fails, and the file is put back as it was.
    const broken = ctx.readFile(CONFIG) + 'firsts:\n  rareItems: [trident\n'
    ctx.writeFile(CONFIG, broken)
    await ctx.command(admin, '/season config set pets.maxPerPlayer 4', /^pets\.maxPerPlayer wasn't changed: config\.yml, line \d+/)
    assert(ctx.readFile(CONFIG) === broken, 'config.yml is put back as it was')
    await ctx.command(admin, '/season config get pets.maxPerPlayer', /^pets\.maxPerPlayer = 3$/)
    ctx.writeFile(CONFIG, bundled)
    await ctx.command(admin, '/season reload', /^Reloaded /)

    // Webhook URLs are never shown or set.
    const discord = await ctx.webhook()
    ctx.writeFile('plugins/Seasons/secrets.yml', `schema-version: 1\ndiscordWebhook: "${discord.url}"\nadminWebhook: "${discord.url}"\n`)
    ctx.writeFile(CONFIG, bundled + `stash: "${discord.url}"\n`)
    await ctx.command(admin, '/season reload', /^Reloaded /)
    for (const path of ['discordWebhook', 'adminWebhook', 'secrets.discordWebhook', 'zones.webhook']) {
      await ctx.command(admin, `/season config get ${path}`, SECRET)
    }
    await ctx.command(admin, '/season config set discordWebhook https://example.com/hook', SECRET)
    await ctx.command(admin, '/season config get stash', /^There's no setting called stash\./)
    await ctx.sync(admin, [admin, pat], 5)
    const url = discord.url.replace('https://', '')
    assert(!heard.some((line) => line.includes(url)), 'no reply shows the URL')
    assert(!ctx.serverLog().includes(url), 'the server log doesn\'t show the URL')
    assert(!ctx.readFile(AUDIT()).includes(url), 'the audit log doesn\'t show the URL')
    ctx.writeFile(CONFIG, bundled)

    // Every change is audited, with who made it.
    const audit = ctx.readFile(AUDIT())
    for (const entry of [
      /"actor":"SeasonsAdmin","action":"config","target":"config\.yml hof\.entry\.cooldown","result":"ok","details":"0s -> 30s"/,
      /"actor":"SeasonsAdmin","action":"config","target":"config\.yml schedule\.reminders","result":"ok","details":"\[7d, 1d, 1h, 10m\] -> \[2d, 1h\]"/,
      /"actor":"SeasonsAdmin","action":"config","target":"config\.yml pets\.maxPerPlayer","result":"ok","details":"5 -> 3"/
    ]) {
      assert(entry.test(audit), `the audit log has ${entry}: ${audit}`)
    }
    assert(!/pets\.maxPerPlayer","result":"ok","details":"3 -> 4"/.test(audit), 'the set that was put back isn\'t audited')

    // Only config admins.
    await ctx.command(pat, '/season config get pets.enabled', /^(Unknown or incomplete command|Incorrect argument for command)/)
  }
}
