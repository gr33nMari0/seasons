// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The Help page's Markdown reader (M9): front matter dropped, docs links sent to the public docs,
// only https links followed, and the blocks the guides use. The DOM is a stand-in: nothing here
// parses HTML, so a small fake document is enough to see what would be built.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { module } from './paths.mjs'

class Node {
  constructor (tag) { this.tag = tag; this.children = []; this.attrs = {}; this.dataset = {} }
  append (...kids) { this.children.push(...kids) }
  setAttribute (k, v) { this.attrs[k] = v }
  addEventListener () {}
  replaceChildren () { this.children = [] }
  get text () { return this.tag === '#text' ? this.data : this.children.map((c) => c.text).join('') }
}
globalThis.Node = Node
globalThis.document = {
  createElement: (tag) => new Node(tag),
  createTextNode: (data) => Object.assign(new Node('#text'), { data }),
  createElementNS: (_, tag) => new Node(tag)
}

const { body, render, target } = await module('help.mjs')
const DOCS = 'https://docs.example/docs/'

test('front matter is dropped', () => {
  assert.equal(body('---\ntitle: X\n---\n\nHello'), 'Hello')
  assert.equal(body('No front matter'), 'No front matter')
})

test('links: docs paths to the public docs, https as written, anything else is text', () => {
  assert.equal(target('/docs/players/your-vault/', DOCS), 'https://docs.example/docs/players/your-vault/')
  assert.equal(target('/connect/', DOCS), 'https://docs.example/connect/')
  assert.equal(target('https://example.com/rules', DOCS), 'https://example.com/rules')
  assert.equal(target('javascript:alert(1)', DOCS), null)
  assert.equal(target('http://example.com', DOCS), null)
})

test('blocks: headings below the page title, lists, tables, code, and text kept as text', () => {
  const nodes = render('---\ntitle: T\n---\n## Getting there\n\n- **From a bed:** `/hof`\n- A [link](/docs/x/)\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n\n```text\n/web link\n```\n\n<script>alert(1)</script>', DOCS)
  assert.deepEqual(nodes.map((n) => n.tag), ['h3', 'ul', 'div', 'pre', 'p'])
  assert.equal(nodes[0].text, 'Getting there')
  const link = nodes[1].children[1].children.find((c) => c.tag === 'a')
  assert.equal(link.attrs.href, 'https://docs.example/docs/x/')
  assert.equal(nodes[4].text, '<script>alert(1)</script>', 'markup stays text')
})
