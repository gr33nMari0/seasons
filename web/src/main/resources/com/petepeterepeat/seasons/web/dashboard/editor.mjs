// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Editing a dashboard's layout (M9): a bar of the viewer's dashboards and what to do with them, and
// on each widget a handle to drag, a corner to resize and a button to remove it. With the keyboard,
// a focused widget moves with the arrow keys and resizes with Shift and the arrows; each change is
// announced. Positions are set through the CSSOM, which the page's policy allows; no inline style
// is ever written into the markup.
import * as L from './layout.mjs'
import { el, px, replace } from './dom.mjs'

/** Puts each widget node where its item says, through CSS custom properties the stylesheet reads. */
export function applyPlacement (grid, items) {
  grid.classList.add('placed')
  const byId = new Map(items.map((i) => [i.id, i]))
  for (const node of grid.children) {
    const item = byId.get(node.dataset.slot)
    if (!item) continue
    node.style.setProperty('--gc', `${item.x + 1} / span ${item.w}`)
    node.style.setProperty('--gr', `${item.y + 1} / span ${item.h}`)
    node.style.setProperty('--span', item.w > 6 ? 'span 2' : 'span 1')
    node.style.setProperty('--order', String(item.y * L.COLUMNS + item.x))
  }
}

/** The grid's cell size in pixels, for turning a drag into columns and rows. */
function metrics (grid) {
  const style = getComputedStyle(grid)
  const gap = parseFloat(style.columnGap) || 16
  const row = parseFloat(style.gridAutoRows) || 80
  const width = grid.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight)
  return { col: (width - gap * (L.COLUMNS - 1)) / L.COLUMNS + gap, row: row + gap }
}

/**
 * Makes the widgets in `grid` movable, resizable and removable.
 *
 * @param state { items } of the dashboard being edited, changed in place
 * @param onChange called with the new items after each change
 * @param onRemove called once a widget is taken off, so the page can draw itself again
 * @param announce says a change aloud through the page's live region
 */
export function makeEditable (app, grid, state, { onChange, onRemove, announce, titleOf }) {
  const commit = (items, id) => {
    state.items = items
    applyPlacement(grid, items)
    onChange(items)
    const item = items.find((i) => i.id === id)
    if (item) {
      const d = L.describe(item, titleOf(id))
      announce(app.t('layout.moved', { widget: d.title, column: d.column, row: d.row, wide: d.wide, high: d.high }))
    }
  }

  for (const node of grid.children) {
    const id = node.dataset.slot
    if (!id) continue
    node.tabIndex = 0
    node.setAttribute('aria-roledescription', app.t('layout.movable'))
    node.setAttribute('aria-describedby', 'layout-keys')

    const grip = el('button', { type: 'button', class: 'edit-grip', 'aria-label': app.t('layout.move', { widget: titleOf(id) }), tabindex: '-1' }, px('grip'))
    const size = el('button', { type: 'button', class: 'edit-size', 'aria-label': app.t('layout.resize', { widget: titleOf(id) }), tabindex: '-1' }, px('resize'))
    const drop = el('button', {
      type: 'button',
      class: 'edit-remove',
      'aria-label': app.t('layout.remove', { widget: titleOf(id) }),
      on: { click: () => { commit(L.remove(state.items, id), null); announce(app.t('layout.removed', { widget: titleOf(id) })); onRemove(id) } }
    }, px('close'))
    node.append(el('div', { class: 'edit-controls' }, grip, drop), size)

    const drag = (handle, kind) => {
      handle.addEventListener('pointerdown', (e) => {
        if (e.button !== 0) return
        e.preventDefault()
        handle.setPointerCapture(e.pointerId)
        const start = state.items.find((i) => i.id === id)
        const m = metrics(grid)
        const x0 = e.clientX
        const y0 = e.clientY
        node.classList.add('dragging')
        let last = ''
        const onMove = (ev) => {
          const dx = Math.round((ev.clientX - x0) / m.col)
          const dy = Math.round((ev.clientY - y0) / m.row)
          const key = `${dx},${dy}`
          if (key === last) return
          last = key
          const items = kind === 'move'
            ? L.move(state.items, id, start.x + dx, start.y + dy)
            : L.resize(state.items, id, start.w + dx, start.h + dy)
          state.items = items
          applyPlacement(grid, items)
        }
        const onUp = () => {
          handle.removeEventListener('pointermove', onMove)
          handle.removeEventListener('pointerup', onUp)
          handle.removeEventListener('pointercancel', onUp)
          node.classList.remove('dragging')
          commit(state.items, id)
        }
        handle.addEventListener('pointermove', onMove)
        handle.addEventListener('pointerup', onUp)
        handle.addEventListener('pointercancel', onUp)
      })
    }
    drag(grip, 'move')
    drag(size, 'resize')

    node.addEventListener('keydown', (e) => {
      if (e.target !== node) return
      const step = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }[e.key]
      if (step) {
        e.preventDefault()
        const item = state.items.find((i) => i.id === id)
        commit(e.shiftKey
          ? L.resize(state.items, id, item.w + step[0], item.h + step[1])
          : L.move(state.items, id, item.x + step[0], item.y + step[1]), id)
      } else if (e.key === 'Delete' || e.key === 'Backspace') {
        e.preventDefault()
        drop.click()
      }
    })
  }
}

/**
 * The bar above an edited dashboard: the viewer's dashboards as tabs, a widget to add, and what to
 * do with this one.
 *
 * @param ctx { store, active, addable, titleOf, onSelect, onNew, onRename, onDelete, onScreen,
 *     onAdd, onReset, onPaste, onDone, admin, announce }
 */
export function editorBar (app, ctx) {
  const current = ctx.active
  const status = el('p', { class: 'edit-status', role: 'status' })
  const say = (text) => { status.textContent = text }
  const copy = async (text, done) => {
    try {
      await navigator.clipboard.writeText(text)
      say(done)
    } catch {
      say(text)
    }
  }

  const tabs = el('div', { class: 'edit-tabs', role: 'tablist', 'aria-label': app.t('layout.dashboards') },
    el('button', { type: 'button', role: 'tab', 'aria-selected': String(!current), on: { click: () => ctx.onSelect(null) } }, app.t('layout.default')),
    ctx.store.dashboards.map((d) => el('button', {
      type: 'button',
      role: 'tab',
      'aria-selected': String(current?.id === d.id),
      on: { click: () => ctx.onSelect(d.id) }
    }, d.name, ctx.store.screen === d.id ? el('span', { class: 'tab-screen', title: app.t('layout.on-screen') }, ' ', px('server')) : null)),
    el('button', { type: 'button', class: 'edit-new', on: { click: () => ctx.onNew() } }, px('plus'), app.t('layout.new')))

  const select = el('select', { id: 'layout-add', 'aria-label': app.t('layout.add') },
    el('option', { value: '' }, app.t('layout.add')),
    ctx.addable.map((w) => el('option', { value: w }, ctx.titleOf(w))))
  const addRow = el('div', { class: 'edit-add' },
    select,
    el('button', { type: 'button', class: 'button small', on: { click: () => { if (select.value) ctx.onAdd(select.value) } } }, px('plus'), app.t('layout.add-button')))

  const name = current ? el('input', { type: 'text', value: current.name, maxlength: '40', 'aria-label': app.t('layout.rename') }) : null
  const paste = el('input', { type: 'text', placeholder: 'S1.…', 'aria-label': app.t('layout.paste'), spellcheck: 'false', autocomplete: 'off' })

  const actions = el('div', { class: 'edit-actions' },
    current
      ? [
          el('label', { class: 'edit-name' }, el('span', {}, app.t('layout.rename')), name,
            el('button', { type: 'button', class: 'quiet-button', on: { click: () => ctx.onRename(name.value) } }, app.t('layout.save-name'))),
          el('button', { type: 'button', class: 'quiet-button', 'aria-pressed': String(ctx.store.screen === current.id), on: { click: () => ctx.onScreen(current.id) } }, px('server'), app.t('layout.screen')),
          el('button', { type: 'button', class: 'quiet-button', on: { click: () => copy(ctx.code(), app.t('layout.copied')) } }, px('copy'), app.t('layout.copy')),
          ctx.admin ? el('button', { type: 'button', class: 'quiet-button', on: { click: () => copy(ctx.webYml(), app.t('layout.web-yml-copied')) } }, px('terminal'), app.t('layout.web-yml')) : null,
          el('button', { type: 'button', class: 'quiet-button', on: { click: () => ctx.onReset() } }, px('refresh'), app.t('layout.reset')),
          el('button', { type: 'button', class: 'quiet-button danger', on: { click: () => ctx.onDelete(current.id) } }, px('trash'), app.t('layout.delete'))
        ]
      : el('p', { class: 'quiet' }, app.t('layout.default-note')),
    el('div', { class: 'edit-paste' }, paste,
      el('button', { type: 'button', class: 'quiet-button', on: { click: () => { if (!ctx.onPaste(paste.value)) say(app.t('layout.bad-code')) } } }, app.t('layout.paste-button'))))

  return el('section', { class: 'edit-bar', 'aria-label': app.t('layout.editing') },
    el('div', { class: 'edit-head' },
      el('h2', {}, px('layout'), app.t('layout.editing')),
      el('button', { type: 'button', class: 'button', data: { action: 'layout-done' }, on: { click: () => ctx.onDone() } }, px('check'), app.t('layout.done'))),
    tabs,
    current ? addRow : null,
    actions,
    el('p', { id: 'layout-keys', class: 'quiet small' }, app.t('layout.keys')),
    status)
}

/** A widget this viewer can't see, kept in a layout someone shared. */
export function unavailable (app, id, title) {
  return {
    node: el('section', { class: ['widget', 'widget-unavailable'], data: { widget: 'unavailable', slot: id } },
      el('h2', {}, title),
      el('p', { class: 'empty' }, app.t('layout.unavailable'))),
    refresh: async () => {}
  }
}

export { replace }
