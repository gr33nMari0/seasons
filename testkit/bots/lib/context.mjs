// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// What a scenario gets: bots that play the player side, and an op'd admin bot for admin commands.
// Scenarios drive the server only through these, so the same suite runs against every adapter.
import mineflayer from 'mineflayer'

const JOIN_TIMEOUT_MS = 60_000
const REPLY_TIMEOUT_MS = 10_000

export class ScenarioContext {
  constructor ({ host, port, version, adminName, log }) {
    Object.assign(this, { host, port, version, adminName, log })
    this.bots = []
  }

  /** Joins a player bot and resolves once it has spawned. */
  async join (username) {
    const bot = mineflayer.createBot({
      host: this.host,
      port: this.port,
      username,
      version: this.version,
      auth: 'offline',
      hideErrors: false
    })
    this.bots.push(bot)
    bot.on('messagestr', (text) => this.log(`[${username}] <- ${text}`))
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
   * Runs a command as a bot and resolves with the first chat line matching `expect`.
   * Rejects if no matching line arrives in time.
   */
  command (bot, command, expect, timeoutMs = REPLY_TIMEOUT_MS) {
    return new Promise((resolve, reject) => {
      const onMessage = (text) => {
        if (expect.test(text)) {
          cleanup()
          resolve(text)
        }
      }
      const timer = setTimeout(() => {
        cleanup()
        reject(new Error(`no reply matching ${expect} to "${command}" within ${timeoutMs} ms`))
      }, timeoutMs)
      const cleanup = () => { clearTimeout(timer); bot.off('messagestr', onMessage) }
      bot.on('messagestr', onMessage)
      this.log(`[${bot.username}] -> ${command}`)
      bot.chat(command)
    })
  }

  /** Disconnects every bot this scenario joined. */
  async close () {
    await Promise.all(this.bots.map((bot) => new Promise((resolve) => {
      if (!bot._client || bot._client.ended) return resolve()
      bot.once('end', resolve)
      bot.quit()
      setTimeout(resolve, 5_000)
    })))
    this.bots = []
  }
}

export function assert (condition, message) {
  if (!condition) throw new Error(`assertion failed: ${message}`)
}
