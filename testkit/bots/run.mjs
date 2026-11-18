// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Runs scenario sessions against a running server and writes results.json.
// Usage: node run.mjs --host 127.0.0.1 --port 25599 --version 26.1 --admin SeasonsAdmin --out <dir>
//        [--server-dir <dir>] [--tls <webhook.p12>] [--webhook-port <n>] [--shared] [step...]
// A step is <scenario> or <scenario>:<session>. With no steps, session 1 of every file in
// scenarios/ runs. Exit code 0 means every step passed.
// A scenario either exports run(ctx), or sessions: [fn, fn, ...] with "// @sessions <n>" at the
// top of its file; the server restarts between sessions, so a session that isn't its scenario's
// last ends this run, and the next boot carries on with the following session.
// With --shared, the server runs many scenarios: after each scenario's last session the runner
// asks the test plugin to reset the server to a fresh boot (docs/adr/0003), writing
// "@@scenario <request>" to its standard output and reading one line back from its standard input.
// A reset that leaves anything behind fails the scenario before it, and ends this run so the next
// boot starts on a fresh world.
import { appendFileSync, mkdirSync, readdirSync, renameSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { createInterface } from 'node:readline'
import { parseArgs } from 'node:util'
import { ScenarioContext } from './lib/context.mjs'

const SCENARIO_TIMEOUT_MS = 120_000
const here = import.meta.dirname

const { values, positionals } = parseArgs({
  allowPositionals: true,
  options: {
    host: { type: 'string', default: '127.0.0.1' },
    port: { type: 'string', default: '25565' },
    version: { type: 'string' },
    admin: { type: 'string', default: 'SeasonsAdmin' },
    out: { type: 'string', default: '.' },
    'server-dir': { type: 'string', default: '.' },
    tls: { type: 'string' },
    'webhook-port': { type: 'string', default: '25601' },
    shared: { type: 'boolean', default: false }
  }
})

mkdirSync(values.out, { recursive: true })
const runnerLog = join(values.out, 'runner.log')
// After a crash point halts the server, nothing reads standard output any more; the lines still
// go to runner.log.
process.stdout.on('error', () => {})
const log = (line) => {
  const text = `[scenarios] ${line}`
  appendFileSync(runnerLog, text + '\n')
  if (!process.stdout.destroyed) process.stdout.write(text + '\n')
}

// Requests to the test plugin, one at a time, each answered by one line.
const replies = []
let controlOpen = true
const waiting = []
createInterface({ input: process.stdin })
  .on('line', (line) => (waiting.length ? waiting.shift()(line) : replies.push(line)))
  .on('close', () => {
    controlOpen = false
    while (waiting.length) waiting.shift()(null)
  })
function request (text) {
  if (!controlOpen || process.stdout.destroyed) return Promise.resolve(null)
  process.stdout.write(`@@scenario ${text}\n`)
  return new Promise((resolve) => (replies.length ? resolve(replies.shift()) : waiting.push(resolve)))
}

const files = readdirSync(join(here, 'scenarios')).filter((f) => f.endsWith('.mjs')).sort()
const steps = (positionals.length ? positionals : files.map((f) => f.replace(/\.mjs$/, '')))
  .map((step) => {
    const [name, session = '1'] = step.split(':')
    return { name, session: Number(session) }
  })

const startedAt = Date.now()
const results = []
let current = null

/** Written after every step, so the results survive a halt that cuts the run short. */
function save (done) {
  const file = join(values.out, 'results.json')
  // startedAt and finishedAt (epoch ms) feed Gradle's scenario timing report.
  writeFileSync(`${file}.tmp`, JSON.stringify({ startedAt, finishedAt: Date.now(), done, current, results }, null, 2))
  renameSync(`${file}.tmp`, file)
}

for (const { name, session } of steps) {
  if (!files.includes(`${name}.mjs`)) {
    results.push({ name, session, passed: false, error: 'no such scenario', ms: 0 })
    continue
  }
  const { default: scenario } = await import(join(here, 'scenarios', `${name}.mjs`))
  const sessions = scenario.sessions ? scenario.sessions.length : 1
  const ctx = new ScenarioContext({
    host: values.host,
    port: Number(values.port),
    version: values.version,
    adminName: values.admin,
    serverDir: values['server-dir'],
    tls: values.tls,
    webhookPort: Number(values['webhook-port']),
    log
  })
  const body = scenario.sessions ? scenario.sessions[session - 1] : scenario.run
  current = { name, session }
  save(false)
  await request(`step ${name} ${session}`)
  const started = Date.now()
  log(`START ${name} (session ${session})`)
  const result = { name, session, passed: true, ms: 0 }
  try {
    let timer
    await Promise.race([
      body ? body(ctx) : Promise.reject(new Error(`${name} has no session ${session}`)),
      new Promise((_resolve, reject) => {
        timer = setTimeout(() => reject(new Error(`timed out after ${SCENARIO_TIMEOUT_MS} ms`)), SCENARIO_TIMEOUT_MS)
      })
    ]).finally(() => clearTimeout(timer))
    log(`PASS  ${name}`)
  } catch (err) {
    Object.assign(result, { passed: false, error: String(err?.stack ?? err) })
    log(`FAIL  ${name}: ${err?.message ?? err}`)
  } finally {
    await ctx.close()
  }
  result.ms = Date.now() - started
  results.push(result)
  current = null
  save(false)
  if (session < sessions) break // the server restarts before the next session
  if (values.shared) {
    const resetStarted = Date.now()
    const reply = await request('reset')
    result.resetMs = Date.now() - resetStarted
    if (reply === null) break // the server has stopped
    if (reply !== 'ok') {
      Object.assign(result, { passed: false, error: `the reset after it: ${reply}`, leaked: true })
      log(`LEAK  ${name}: ${reply}`)
      save(false)
      break
    }
    save(false)
  }
}

save(true)
const failed = results.filter((r) => !r.passed).length
log(`${results.length - failed} passed, ${failed} failed`)
process.exit(failed === 0 ? 0 : 1)
