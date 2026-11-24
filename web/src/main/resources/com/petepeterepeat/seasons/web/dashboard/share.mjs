// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Sharing a page (M9): the device's share sheet where there is one (phones, tablets), else the link
// copied. Shared links unfurl with the preview Seasons Web draws for the page.
import { el, px } from './dom.mjs'

export function shareButton (app) {
  const status = el('span', { class: 'sr-only', role: 'status' })
  const button = el('button', {
    type: 'button',
    class: 'icon-button',
    data: { action: 'share' },
    'aria-label': app.t('share.button'),
    title: app.t('share.button'),
    on: {
      click: async () => {
        const url = location.href
        try {
          if (navigator.share) {
            await navigator.share({ title: document.title, url })
            return
          }
          await navigator.clipboard.writeText(url)
          status.textContent = app.t('share.copied')
          button.dataset.copied = 'true'
          setTimeout(() => delete button.dataset.copied, 1500)
        } catch {}
      }
    }
  }, px('share'), status)
  return button
}
