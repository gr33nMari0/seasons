// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// What a scenario gets: bots that play the player side, an op'd admin bot for admin commands,
// the server's files, and an https webhook receiver standing in for Discord.
// Scenarios drive the server only through these, so the same suite runs against every adapter.
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import https from 'node:https'
import { dirname, join } from 'node:path'
import mineflayer from 'mineflayer'

const JOIN_TIMEOUT_MS = 60_000
const REPLY_TIMEOUT_MS = 10_000
const POLL_MS = 200
const MOVEMENT_PACKETS = new Set(['position', 'look', 'position_look', 'flying'])
/** Fixed, so a webhook URL written in one session still works in the next. */
const WEBHOOK_PORT = 25601

export const SECOND = 1_000
export const MINUTE = 60 * SECOND
export const HOUR = 60 * MINUTE
export const DAY = 24 * HOUR

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

/** An ISO-8601 time `ms` from now, in UTC, as /season schedule end takes it. */
export const isoIn = (ms) => new Date(Date.now() + ms).toISOString()

/** Resolves once `check()` is true; rejects with `what` after `timeoutMs`. */
export async function waitUntil (check, timeoutMs, what) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (check()) return
    await sleep(POLL_MS)
  }
  throw new Error(`timed out after ${timeoutMs} ms waiting for ${what}`)
}

export class ScenarioContext {
  constructor ({ host, port, version, adminName, serverDir = '.', tls, log }) {
    Object.assign(this, { host, port, version, adminName, serverDir, tls, log })
    this.bots = []
    this.servers = []
  }

  /**
   * Joins a player bot and resolves once it has spawned. Unless `physics` is asked for, the bot
   * stands still and sends no movement packets: through the protocol translation
   * (docs/adr/0001) Mineflayer's once-a-second position packet gets the bot kicked with "Invalid
   * move player packet", and scenarios that only chat and watch don't need to move.
   */
  async join (username, { physics = false } = {}) {
    const bot = mineflayer.createBot({
      host: this.host,
      port: this.port,
      username,
      version: this.version,
      auth: 'offline',
      hideErrors: false,
      physicsEnabled: physics
    })
    this.bots.push(bot)
    if (!physics) {
      const write = bot._client.write.bind(bot._client)
      bot._client.write = (name, params) => {
        if (MOVEMENT_PACKETS.has(name)) return
        write(name, params)
      }
    }
    bot.seasonsBars = new Map()
    bot.on('messagestr', (text) => this.log(`[${username}] <- ${text}`))
    bot.on('bossBarCreated', (bar) => bot.seasonsBars.set(bar.entityUUID, bar))
    bot.on('bossBarDeleted', (bar) => bot.seasonsBars.delete(bar.entityUUID))
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`${username} did not spawn within ${JOIN_TIMEOUT_MS} ms`)), JOIN_TIMEOUT_MS)
      const fail = (why) => { clearTimeout(timer); reject(new Error(`${username} could not join: ${why}`)) }
      bot.once('spawn', () => { clearTimeout(timer); resolve() })
      bot.once('kicked', (reason) => fail(`kicked: ${JSON.stringify(reason)}`))
      bot.once('error', (err) => fail(err.message))
    })
    this.log(`${username} spawned at ${bot.entity.position}`)
    return bot
  }

  /** Joins the admin bot. The test plugin makes it an op as it joins. */
  admin () {
    return this.join(this.adminName)
  }

  /**
   * Resolves with the chat lines matching every pattern in `expect` (one pattern or a list),
   * each matched by a different line, in any order. Rejects if they don't all arrive in time.
   */
  waitForMessages (bot, expect, timeoutMs = REPLY_TIMEOUT_MS, what = 'messages') {
    const patterns = Array.isArray(expect) ? [...expect] : [expect]
    // Filled, not holes: every() skips holes and would resolve after the first match.
    const matched = new Array(patterns.length).fill(undefined)
    return new Promise((resolve, reject) => {
      const onMessage = (text) => {
        const i = patterns.findIndex((p, j) => matched[j] === undefined && p.test(text))
        if (i < 0) return
        matched[i] = text
        if (matched.every((m) => m !== undefined)) {
          cleanup()
          resolve(Array.isArray(expect) ? matched : matched[0])
        }
      }
      const timer = setTimeout(() => {
        cleanup()
        const missing = patterns.filter((_p, j) => matched[j] === undefined)
        reject(new Error(`no line matching ${missing.join(', ')} for ${what} within ${timeoutMs} ms`))
      }, timeoutMs)
      const cleanup = () => { clearTimeout(timer); bot.off('messagestr', onMessage) }
      bot.on('messagestr', onMessage)
    })
  }

  /** Resolves with no value if no chat line matches `pattern` within `ms`; rejects if one does. */
  expectNoMessage (bot, pattern, ms) {
    return new Promise((resolve, reject) => {
      const onMessage = (text) => {
        if (pattern.test(text)) {
          clearTimeout(timer)
          bot.off('messagestr', onMessage)
          reject(new Error(`unexpected line matching ${pattern}: ${text}`))
        }
      }
      const timer = setTimeout(() => { bot.off('messagestr', onMessage); resolve() }, ms)
      bot.on('messagestr', onMessage)
    })
  }

  /**
   * Runs a command as a bot and resolves with the chat lines matching `expect` (see
   * waitForMessages). Rejects if they don't all arrive in time.
   */
  command (bot, command, expect, timeoutMs = REPLY_TIMEOUT_MS) {
    const reply = this.waitForMessages(bot, expect, timeoutMs, `"${command}"`)
    this.log(`[${bot.username}] -> ${command}`)
    bot.chat(command)
    return reply
  }

  /** The titles of the boss bars a bot currently sees, as plain text. */
  bossBars (bot) {
    return [...bot.seasonsBars.values()].map((bar) => bar.title.toString())
  }

  /** Resolves once a bot sees a boss bar whose title matches `pattern`. */
  async waitForBossBar (bot, pattern, timeoutMs = REPLY_TIMEOUT_MS) {
    await waitUntil(() => this.bossBars(bot).some((t) => pattern.test(t)), timeoutMs, `a boss bar matching ${pattern}`)
    return this.bossBars(bot).find((t) => pattern.test(t))
  }

  /** Resolves once a bot sees no boss bars. */
  waitForNoBossBar (bot, timeoutMs = REPLY_TIMEOUT_MS) {
    return waitUntil(() => bot.seasonsBars.size === 0, timeoutMs, `${bot.username}'s boss bars to go`)
  }

  /** Writes a file relative to the server's run directory, such as plugins/Seasons/config.yml. */
  writeFile (path, text) {
    const file = join(this.serverDir, path)
    mkdirSync(dirname(file), { recursive: true })
    writeFileSync(file, text)
    this.log(`wrote ${path}`)
  }

  /** Reads a file relative to the server's run directory. */
  readFile (path) {
    return readFileSync(join(this.serverDir, path), 'utf8')
  }

  /**
   * Starts an https webhook receiver standing in for Discord. Every JSON body posted to it lands
   * in `posts`, in order.
   */
  async webhook () {
    if (!this.tls) throw new Error('no --tls certificate for the webhook receiver')
    const posts = []
    const server = https.createServer({ pfx: readFileSync(this.tls), passphrase: 'changeit' }, (req, res) => {
      let body = ''
      req.setEncoding('utf8')
      req.on('data', (chunk) => { body += chunk })
      req.on('end', () => {
        try {
          posts.push(JSON.parse(body))
        } catch {
          posts.push({ invalid: body })
        }
        this.log(`[webhook] <- ${body}`)
        res.writeHead(204)
        res.end()
      })
    })
    await new Promise((resolve, reject) => {
      server.once('error', reject)
      server.listen(WEBHOOK_PORT, '127.0.0.1', resolve)
    })
    this.servers.push(server)
    return {
      url: `https://127.0.0.1:${WEBHOOK_PORT}/webhook`,
      posts,
      /** Resolves with the first post whose content matches `pattern`. */
      waitFor: async (pattern, timeoutMs = REPLY_TIMEOUT_MS) => {
        await waitUntil(() => posts.some((p) => pattern.test(p.content ?? '')), timeoutMs, `a webhook post matching ${pattern}`)
        return posts.find((p) => pattern.test(p.content ?? ''))
      }
    }
  }

  /**
   * Retries `check` (an async function that throws when not yet true) until it passes, and
   * resolves with its value. Rejects with the last failure after `timeoutMs`.
   */
  async eventually (check, timeoutMs = REPLY_TIMEOUT_MS) {
    const deadline = Date.now() + timeoutMs
    let last
    while (Date.now() < deadline) {
      try {
        return await check()
      } catch (err) {
        last = err
        await sleep(500)
      }
    }
    throw new Error(`still failing after ${timeoutMs} ms: ${last?.message ?? last}`)
  }

  /** How many items a player carries, inventory, armour and off hand, as /clear counts them. */
  async itemCount (admin, name) {
    const line = await this.command(admin, `/clear ${name} * 0`, /^(Found \d+ matching item|No items were found on player)/)
    const match = /^Found (\d+) matching item/.exec(line)
    return match ? Number(match[1]) : 0
  }

  /** The text of /data get entity for one path, or null when the entity has no such data. */
  async entityData (admin, name, path) {
    const line = await this.command(admin, `/data get entity ${name} ${path}`, /(has the following entity data: |^Found no elements matching )/)
    const at = line.indexOf('has the following entity data: ')
    return at < 0 ? null : line.slice(at + 'has the following entity data: '.length)
  }

  /** Whether an /execute if ... condition holds, as the admin sees it. */
  async holds (admin, condition) {
    const line = await this.command(admin, `/execute if ${condition}`, /^Test (passed|failed)/)
    return line.startsWith('Test passed')
  }

  /** A player's server-side position, from /data get entity Pos. */
  async position (admin, name) {
    const text = await this.entityData(admin, name, 'Pos')
    const [x, y, z] = text.replace(/[[\]d\s]/g, '').split(',').map(Number)
    return { x, y, z }
  }

  /**
   * What Seasons holds for a player and season, through the scenario hooks (docs/adr/0003):
   * { present, stacks, items, ender, vault, completed, visible, archivedThrough, pending }.
   */
  async archive (admin, name, season) {
    const [summary, record] = await this.command(admin, `/scenario archive ${name} ${season}`, [/^Archive /, /^Record /])
    const number = (key, text) => Number(new RegExp(`${key}=(-?\\d+)`).exec(text)?.[1] ?? NaN)
    return {
      present: !/ none$/.test(summary),
      stacks: number('stacks', summary),
      items: number('items', summary),
      ender: number('ender', summary),
      vault: number('vault', summary),
      completed: number('completed', summary),
      visible: number('visible', summary),
      archivedThrough: number('archivedThrough', record),
      pending: number('pending', record)
    }
  }

  /** Resolves once the bot is disconnected, such as when the server stops. */
  disconnected (bot, timeoutMs = 60_000) {
    return new Promise((resolve, reject) => {
      if (bot._client.ended) return resolve()
      const timer = setTimeout(() => reject(new Error(`${bot.username} still connected after ${timeoutMs} ms`)), timeoutMs)
      bot.once('end', () => { clearTimeout(timer); resolve() })
    })
  }

  /** Disconnects a bot and resolves once it has left. */
  async leave (bot) {
    const gone = this.disconnected(bot, 10_000)
    bot.quit()
    await gone
    this.bots = this.bots.filter((b) => b !== bot)
  }

  /** Disconnects every bot and stops every receiver this scenario started. */
  async close () {
    await Promise.all(this.bots.map((bot) => new Promise((resolve) => {
      if (!bot._client || bot._client.ended) return resolve()
      bot.once('end', resolve)
      bot.quit()
      setTimeout(resolve, 5_000)
    })))
    this.bots = []
    await Promise.all(this.servers.map((server) => new Promise((resolve) => {
      server.closeAllConnections()
      server.close(() => resolve())
    })))
    this.servers = []
  }
}

export function assert (condition, message) {
  if (!condition) throw new Error(`assertion failed: ${message}`)
}
