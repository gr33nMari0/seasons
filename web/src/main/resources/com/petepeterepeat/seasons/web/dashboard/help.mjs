// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Help (M9): the players' guides bundled from the public docs, this server's own links and rules
// from web.yml, and a link to the full docs. Guides and rules are Markdown, drawn here as text
// nodes by a small reader that knows headings, paragraphs, lists, tables, code and links: nothing is
// ever parsed as HTML. A link into the docs goes to the public docs; any other link must be https.
import { getText } from './api.mjs'
import { el, icon, replace } from './dom.mjs'

/** Markdown without its front matter. */
export function body (markdown) {
  const text = String(markdown ?? '').replace(/\r\n/g, '\n')
  if (!text.startsWith('---\n')) return text
  const end = text.indexOf('\n---', 4)
  return end < 0 ? text : text.slice(end + 4).replace(/^\n+/, '')
}

/** Where a link in a guide goes: docs paths to the public docs, https as written, nothing else. */
export function target (href, docs) {
  if (/^https:\/\//.test(href)) return href
  if (href.startsWith('/') && docs) {
    const site = docs.replace(/\/docs\/?$/, '/')
    return new URL(href.replace(/^\//, ''), site).href
  }
  return null
}

/** Inline Markdown as nodes: `code`, **bold**, *emphasis* and [links](…). */
export function inline (text, docs) {
  const out = []
  const re = /(`[^`]+`)|(\*\*[^*]+\*\*)|(\*[^*\s][^*]*\*)|(\[[^\]]+\]\([^)\s]+\))/g
  let last = 0
  for (const m of String(text).matchAll(re)) {
    if (m.index > last) out.push(String(text).slice(last, m.index))
    const t = m[0]
    if (m[1]) out.push(el('code', {}, t.slice(1, -1)))
    else if (m[2]) out.push(el('strong', {}, inline(t.slice(2, -2), docs)))
    else if (m[3]) out.push(el('em', {}, inline(t.slice(1, -1), docs)))
    else {
      const [, label, href] = /^\[([^\]]+)\]\(([^)\s]+)\)$/.exec(t)
      const to = target(href, docs)
      out.push(to ? el('a', { href: to, rel: 'noopener', target: '_blank' }, inline(label, docs)) : inline(label, docs))
    }
    last = m.index + t.length
  }
  if (last < String(text).length) out.push(String(text).slice(last))
  return out
}

const cells = (row) => row.trim().replace(/^\||\|$/g, '').split(/(?<!\\)\|/).map((c) => c.trim().replace(/\\\|/g, '|'))

/** Block Markdown as nodes. Headings start at h3, so a guide sits under the page's own title. */
export function render (markdown, docs) {
  const lines = body(markdown).split('\n')
  const out = []
  let i = 0
  while (i < lines.length) {
    const line = lines[i]
    if (/^\s*$/.test(line)) { i++; continue }
    let m
    if ((m = /^(#{1,4})\s+(.*)$/.exec(line))) {
      out.push(el(`h${Math.min(6, m[1].length + 1)}`, {}, inline(m[2], docs)))
      i++
    } else if (/^```/.test(line)) {
      const code = []
      i++
      while (i < lines.length && !/^```/.test(lines[i])) code.push(lines[i++])
      i++
      out.push(el('pre', { tabindex: '0' }, el('code', {}, code.join('\n'))))
    } else if (/^\s*\|/.test(line) && i + 1 < lines.length && /^\s*\|[\s:|-]+\|\s*$/.test(lines[i + 1])) {
      const head = cells(line)
      i += 2
      const rows = []
      while (i < lines.length && /^\s*\|/.test(lines[i])) rows.push(cells(lines[i++]))
      out.push(el('div', { class: 'table-wrap', tabindex: '0' },
        el('table', {},
          el('thead', {}, el('tr', {}, head.map((h) => el('th', { scope: 'col' }, inline(h, docs))))),
          el('tbody', {}, rows.map((r) => el('tr', {}, r.map((c) => el('td', {}, inline(c, docs)))))))))
    } else if ((m = /^\s*([-*]|\d+\.)\s+/.exec(line))) {
      const ordered = /\d/.test(m[1])
      const items = []
      while (i < lines.length && /^\s*([-*]|\d+\.)\s+/.test(lines[i])) {
        let text = lines[i].replace(/^\s*([-*]|\d+\.)\s+/, '')
        i++
        while (i < lines.length && /^\s{2,}\S/.test(lines[i]) && !/^\s*([-*]|\d+\.)\s+/.test(lines[i])) text += ' ' + lines[i++].trim()
        items.push(el('li', {}, inline(text, docs)))
      }
      out.push(el(ordered ? 'ol' : 'ul', {}, items))
    } else if (/^>\s?/.test(line)) {
      const quote = []
      while (i < lines.length && /^>\s?/.test(lines[i])) quote.push(lines[i++].replace(/^>\s?/, ''))
      out.push(el('blockquote', {}, el('p', {}, inline(quote.join(' '), docs))))
    } else {
      const para = []
      while (i < lines.length && !/^\s*$/.test(lines[i]) && !/^(#{1,4}\s|```|\s*([-*]|\d+\.)\s|>|\s*\|)/.test(lines[i])) para.push(lines[i++].trim())
      out.push(el('p', {}, inline(para.join(' '), docs)))
    }
  }
  return out
}

// The Help page and widget

function frame (name, title, ...children) {
  return el('section', { class: ['widget', `widget-${name}`], data: { widget: name } },
    el('h2', {}, icon('book'), title), ...children)
}

/** The server's own links and rules, from web.yml. */
function serverPart (app) {
  const h = app.config.help ?? {}
  const parts = []
  if (h.links?.length) {
    parts.push(el('ul', { class: 'help-links' }, h.links.map((l) =>
      el('li', {}, el('a', { href: l.url, rel: 'noopener', target: '_blank' }, icon('link'), l.label)))))
  }
  if (h.rules) parts.push(el('div', { class: 'prose' }, render(h.rules, h.docs)))
  return parts.length ? [el('h3', {}, app.t('help.this-server', { name: app.config.name })), parts] : []
}

/** The Help widget: the guides as a list, the server's links, and the full docs. */
export function helpWidget (app) {
  const h = app.config.help ?? { guides: [] }
  const node = frame('help', app.t('help.title'),
    el('ul', { class: 'help-guides' }, (h.guides ?? []).map((g) =>
      el('li', {}, el('a', { href: `/help/${g.name}`, data: { internal: true } }, g.title)))),
    serverPart(app),
    h.docs ? el('p', { class: 'quiet' }, el('a', { href: h.docs, rel: 'noopener', target: '_blank' }, app.t('help.full-docs'))) : null)
  return { node, refresh: async () => {} }
}

/** The Help page: one guide, or the list of them with the server's own part. */
export function helpPage (app, { guide }) {
  const h = app.config.help ?? { guides: [] }
  const content = el('div', { class: 'prose' })
  const found = (h.guides ?? []).find((g) => g.name === guide)
  const node = frame('help-page', found ? found.title : app.t('help.title'), content)

  async function refresh () {
    if (!found) {
      replace(content,
        el('p', {}, app.t('help.intro', { name: app.config.name })),
        el('ul', { class: 'help-guides' }, (h.guides ?? []).map((g) =>
          el('li', {}, el('a', { href: `/help/${g.name}`, data: { internal: true } }, g.title)))),
        serverPart(app),
        h.docs ? el('p', {}, el('a', { class: 'button-link', href: h.docs, rel: 'noopener', target: '_blank' }, app.t('help.full-docs'))) : null)
      return
    }
    const r = await getText(found.file).catch(() => ({ status: 0, text: '' }))
    replace(content,
      el('p', {}, el('a', { href: '/help', data: { internal: true } }, app.t('help.back'))),
      r.status === 200 ? render(r.text ?? '', h.docs) : el('p', { class: 'empty' }, app.t('help.missing')),
      el('p', { class: 'quiet' }, el('a', { href: found.docs, rel: 'noopener', target: '_blank' }, app.t('help.on-docs'))))
  }

  return { node, refresh }
}
