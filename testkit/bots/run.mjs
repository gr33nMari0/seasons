// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Runs acceptance scenarios against a running server and writes results.json.
// Usage: node run.mjs --host 127.0.0.1 --port 25599 --version 26.1 --admin SeasonsAdmin --out <dir> [scenario...]
// With no scenario names, every file in scenarios/ runs. Exit code 0 means every scenario passed.
import { mkdirSync, readdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
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
    out: { type: 'string', default: '.' }
  }
})

const log = (line) => console.log(`[scenarios] ${line}`)

const files = readdirSync(join(here, 'scenarios')).filter((f) => f.endsWith('.mjs')).sort()
const wanted = positionals.length ? positionals : files.map((f) => f.replace(/\.mjs$/, ''))
const results = []

for (const name of wanted) {
  if (!files.includes(`${name}.mjs`)) {
    results.push({ name, passed: false, error: 'no such scenario', ms: 0 })
    continue
  }
  const { default: scenario } = await import(join(here, 'scenarios', `${name}.mjs`))
  const ctx = new ScenarioContext({
    host: values.host,
    port: Number(values.port),
    version: values.version,
    adminName: values.admin,
    log
  })
  const started = Date.now()
  log(`START ${name}`)
  try {
    let timer
    await Promise.race([
      scenario.run(ctx),
      new Promise((_resolve, reject) => {
        timer = setTimeout(() => reject(new Error(`timed out after ${SCENARIO_TIMEOUT_MS} ms`)), SCENARIO_TIMEOUT_MS)
      })
    ]).finally(() => clearTimeout(timer))
    results.push({ name, passed: true, ms: Date.now() - started })
    log(`PASS  ${name}`)
  } catch (err) {
    results.push({ name, passed: false, error: String(err?.stack ?? err), ms: Date.now() - started })
    log(`FAIL  ${name}: ${err?.message ?? err}`)
  } finally {
    await ctx.close()
  }
}

mkdirSync(values.out, { recursive: true })
writeFileSync(join(values.out, 'results.json'), JSON.stringify({ results }, null, 2))
const failed = results.filter((r) => !r.passed).length
log(`${results.length - failed} passed, ${failed} failed`)
process.exit(failed === 0 ? 0 : 1)
