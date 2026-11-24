// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The docs can't drift from the code (AGENTS.md): every command and permission node in the
// generated references has a "since" release, and every command is explained somewhere in the
// guides, not only listed in the reference.
import assert from 'node:assert/strict'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { test } from 'node:test'

const DATA = join(import.meta.dirname, '../src/data')
const DOCS = join(import.meta.dirname, '../src/content/docs/docs')
const read = (path) => JSON.parse(readFileSync(path, 'utf8'))
const commands = ['seasons', 'web'].flatMap((f) => read(join(DATA, `commands/${f}.json`)).commands)
const permissions = read(join(DATA, 'permissions.json')).permissions
const since = read(join(DATA, 'since.json'))

function pages (dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return name === 'reference' ? [] : pages(path)
    return /\.mdx?$/.test(name) ? [path] : []
  })
}

test('every command and permission node has the release it arrived in', () => {
  for (const c of commands) {
    const name = '/' + c.words.join(' ')
    assert.match(since.commands[name] ?? '', /^\d+\.\d+\.\d+$/, `${name} has no entry in site/src/data/since.json`)
  }
  for (const p of permissions) {
    assert.match(since.permissions[p.node] ?? '', /^\d+\.\d+\.\d+$/, `${p.node} has no entry in site/src/data/since.json`)
  }
})

test('every command is explained in a guide, not only listed in the reference', () => {
  const text = pages(DOCS).map((p) => readFileSync(p, 'utf8')).join('\n')
  const esc = (w) => w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  // Arguments may stand between a command's words, as in /hof cell plaque <cell> scale.
  const mentioned = (words) => new RegExp('/' + words.map(esc).join('(?: [^\\s`]+){0,3} ') + '(?![a-z-])').test(text)
  const missing = commands.filter((c) => !mentioned(c.words)).map((c) => '/' + c.words.join(' '))
  assert.deepEqual(missing, [], 'commands no guide mentions: write them into the page that explains their feature')
})

test('every command the spec\'s command tables name exists in the generated reference', () => {
  const spec = readFileSync(join(import.meta.dirname, '../../docs/spec.md'), 'utf8')
  const section = spec.slice(spec.indexOf('## Commands and permissions'), spec.indexOf('## Configuration and data files'))
  const generated = new Set(commands.map((c) => '/' + c.words.join(' ')))
  const prefixes = new Set([...generated].flatMap((name) => name.split(' ').map((_, i, all) => all.slice(0, i + 1).join(' '))))
  const named = [...section.matchAll(/^\| `(\/[a-z]+(?: [a-z][a-z0-9-]*)*)/gm)].map((m) => m[1])
  assert.ok(named.length > 20, 'the spec\'s tables were read')
  // A last word may be an argument's value, as in /season unlock homecoming.
  const missing = named.filter((n) => !prefixes.has(n) && !generated.has(n.split(' ').slice(0, -1).join(' ')))
  assert.deepEqual(missing, [], 'commands the spec names that the plugins don\'t register')
})
