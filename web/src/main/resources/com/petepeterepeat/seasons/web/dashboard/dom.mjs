// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Building the page. Every string becomes a text node, never markup: player-written names, labels
// and waypoints show exactly as written (spec, Security). Nothing in the page's code parses HTML.

const SVG = 'http://www.w3.org/2000/svg'

/**
 * An element: el('a', { href: '/museum', class: 'nav', data: { widget: 'museum' } }, 'Museum').
 * Children may be strings, numbers, nodes, arrays of them, or null and false, which are skipped.
 * Attribute `on` takes event listeners by name.
 */
export function el (tag, attrs = {}, ...children) {
  const node = document.createElement(tag)
  set(node, attrs)
  append(node, children)
  return node
}

function set (node, attrs) {
  for (const [key, value] of Object.entries(attrs ?? {})) {
    if (value === null || value === undefined || value === false) continue
    if (key === 'on') {
      for (const [name, fn] of Object.entries(value)) node.addEventListener(name, fn)
    } else if (key === 'data') {
      for (const [k, v] of Object.entries(value)) {
        if (v !== null && v !== undefined && v !== false) node.dataset[k] = String(v)
      }
    } else if (key === 'class') {
      node.className = Array.isArray(value) ? value.filter(Boolean).join(' ') : value
    } else if (value === true) {
      node.setAttribute(key, '')
    } else {
      node.setAttribute(key, String(value))
    }
  }
}

/** Appends children to a node as `el` does. */
export function append (node, children) {
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false) continue
    node.append(child instanceof Node ? child : document.createTextNode(String(child)))
  }
  return node
}

/** Replaces a node's children. */
export function replace (node, ...children) {
  node.replaceChildren()
  return append(node, children)
}

/** One of our icons from icons.svg. */
export function icon (name, label) {
  const svg = document.createElementNS(SVG, 'svg')
  svg.setAttribute('class', 'icon')
  svg.setAttribute('viewBox', '0 0 24 24')
  if (label) {
    svg.setAttribute('role', 'img')
    svg.setAttribute('aria-label', label)
  } else {
    svg.setAttribute('aria-hidden', 'true')
  }
  const use = document.createElementNS(SVG, 'use')
  use.setAttribute('href', `/app/icons.svg#${name}`)
  svg.append(use)
  return svg
}

/** A player's head, cropped from their skin on Mojang's server, or our own when there is none. */
export function head (skin, name) {
  if (!skin || !/^https:\/\/textures\.minecraft\.net\//.test(skin)) {
    return el('span', { class: 'head head-none', title: name ?? null }, icon('head'))
  }
  return el('span', { class: 'head', title: name ?? null },
    el('img', { class: 'face', src: skin, alt: '', referrerpolicy: 'no-referrer' }),
    el('img', { class: 'hat', src: skin, alt: '', referrerpolicy: 'no-referrer' }))
}

/** One of the design's pixel UI icons (design/icons.svg), in the text's colour. */
export function px (name, label) {
  const svg = document.createElementNS(SVG, 'svg')
  svg.setAttribute('class', 'px')
  svg.setAttribute('viewBox', '0 0 16 16')
  if (label) {
    svg.setAttribute('role', 'img')
    svg.setAttribute('aria-label', label)
  } else {
    svg.setAttribute('aria-hidden', 'true')
  }
  const use = document.createElementNS(SVG, 'use')
  use.setAttribute('href', `/app/design/icons.svg#${name}`)
  svg.append(use)
  return svg
}
