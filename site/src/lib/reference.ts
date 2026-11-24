// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The generated references (./gradlew commandReference permissionReference), joined for the docs:
// every command with its permission nodes' defaults and the release it arrived in.
import seasons from '../data/commands/seasons.json'
import web from '../data/commands/web.json'
import permissionData from '../data/permissions.json'
import since from '../data/since.json'

export interface Command {
  plugin: string
  words: string[]
  name: string
  id: string
  usages: string[]
  description: string
  permissions: string[]
  access: 'everyone' | 'ops' | 'nobody'
  since: string | undefined
}

export interface Permission {
  node: string
  plugin: string
  default: string
  description: string
  children: string[]
  since: string | undefined
}

export const permissions: Permission[] = permissionData.permissions.map((p) => ({
  ...p,
  since: (since.permissions as Record<string, string>)[p.node]
}))

const byNode = new Map(permissions.map((p) => [p.node, p]))

/** Who may run a command by default: the strictest default among its nodes. */
function access (nodes: string[]): Command['access'] {
  const defaults = nodes.map((n) => byNode.get(n)?.default ?? 'op')
  if (defaults.includes('false')) return 'nobody'
  if (defaults.includes('op')) return 'ops'
  return 'everyone'
}

export const commandId = (words: string[]) => 'cmd-' + words.join('-')

export const commands: Command[] = [seasons, web].flatMap((file) =>
  file.commands.map((c) => ({
    plugin: file.plugin,
    words: c.words,
    name: '/' + c.words.join(' '),
    id: commandId(c.words),
    usages: c.usages,
    description: c.description,
    permissions: c.permissions,
    access: access(c.permissions),
    since: (since.commands as Record<string, string>)['/' + c.words.join(' ')]
  }))
)

// The order groups are listed in: what people look for first, then setup, then upkeep. Groups not
// named here follow in the order the plugin registers them.
const ORDER: Record<string, string[]> = {
  season: ['info', 'status', 'init', 'list', 'players', 'schedule', 'end', 'cancel', 'decorate', 'launch', 'unlock', 'lock', 'spawn', 'zone', 'marker', 'config', 'reload', 'validate', 'audit', 'firsts', 'archive', 'unarchive', 'reprocess', 'tp', 'reset'],
  hof: ['hof', 'leave', 'swap', 'claim', 'gate', 'link', 'point', 'wand', 'sel', 'show', 'hall', 'gallery', 'cell', 'stamp', 'import', 'reconcile', 'restore', 'undo', 'list', 'locate', 'tp', 'curate']
}

/** Commands under a root, grouped by their second word: "/hof link", "/hof cell", … */
export function groups (root: string): { name: string; id: string; commands: Command[] }[] {
  const out = new Map<string, Command[]>()
  for (const c of commands.filter((x) => x.words[0] === root)) {
    const key = c.words.length > 1 ? c.words.slice(0, 2).join(' ') : c.words[0]
    if (!out.has(key)) out.set(key, [])
    out.get(key)!.push(c)
  }
  const order = ORDER[root] ?? []
  const rank = (key: string) => {
    const i = order.indexOf(key.split(' ')[1] ?? key)
    return i < 0 ? order.length : i
  }
  return [...out]
    .sort((a, b) => rank(a[0]) - rank(b[0]))
    .map(([key, list]) => ({ name: '/' + key, id: 'group-' + key.replace(/ /g, '-'), commands: list }))
}

export const ACCESS_LABEL: Record<Command['access'], string> = { everyone: 'Everyone', ops: 'Ops', nobody: 'Nobody by default' }
