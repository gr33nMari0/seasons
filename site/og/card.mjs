// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
const q = new URLSearchParams(location.search)
const title = q.get('title') ?? 'Seasons'
document.querySelector('[data-title]').textContent = title
if (title.length > 34) document.querySelector('[data-title]').classList.add('long')
document.querySelector('[data-section]').textContent = q.get('section') ?? ''
document.querySelector('[data-description]').textContent = q.get('description') ?? ''
const art = document.querySelector('[data-art]')
art.src = `/design/art/${(q.get('art') ?? 'logo').replace(/[^a-z0-9-]/g, '')}.svg`
Promise.all([document.fonts.ready, art.decode().catch(() => {})]).then(() => { document.body.dataset.ready = 'true' })
