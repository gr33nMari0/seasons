// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The theme rule for the site, the docs and the dashboard (docs/plans/m9-design-overhaul.md,
// questions 335). Load it as a plain, blocking <script src> in <head>, before any stylesheet that
// reads data-theme, so the page never paints in the wrong theme.
//
// - First visit: follow the system; with no system preference, dark.
// - The toggle (any [data-theme-toggle] button) switches dark and light, kept for this device.
// - When the system theme changes, the page follows it at once and the toggle's choice is dropped.
//   A change while the page was closed counts too: the choice remembers the system it was made
//   against, and is dropped if the system has changed since.
// - <html data-theme-lock="dark"> pins a page dark (the second screen), whatever the rest says, and
//   <html data-theme-lock-paths="/screen"> does so for any address under those paths. A page that
//   changes address itself calls seasonsTheme.refresh() afterwards.
;(function () {
  'use strict'
  var KEY = 'seasons-theme'
  var root = document.documentElement
  var light = window.matchMedia ? window.matchMedia('(prefers-color-scheme: light)') : null

  function system () { return light && light.matches ? 'light' : 'dark' }

  function read () {
    try {
      var saved = JSON.parse(window.localStorage.getItem(KEY) || 'null')
      if (saved && (saved.theme === 'dark' || saved.theme === 'light') && saved.system === system()) return saved.theme
      if (saved) window.localStorage.removeItem(KEY)
    } catch (e) {}
    return null
  }

  function write (theme) {
    try {
      if (theme) window.localStorage.setItem(KEY, JSON.stringify({ theme: theme, system: system() }))
      else window.localStorage.removeItem(KEY)
    } catch (e) {}
  }

  function current () { return root.getAttribute('data-theme') || system() }

  function lockedByPath () {
    var paths = (root.getAttribute('data-theme-lock-paths') || '').split(',')
    for (var i = 0; i < paths.length; i++) {
      var p = paths[i].trim()
      if (p && (location.pathname === p || location.pathname.indexOf(p + '/') === 0)) return 'dark'
    }
    return null
  }

  function apply (theme) {
    var locked = root.getAttribute('data-theme-lock') || lockedByPath()
    var next = locked || theme
    root.setAttribute('data-theme', next)
    var toggles = document.querySelectorAll('[data-theme-toggle]')
    for (var i = 0; i < toggles.length; i++) {
      toggles[i].setAttribute('aria-pressed', next === 'light' ? 'true' : 'false')
      toggles[i].setAttribute('data-current', next)
      var label = toggles[i].getAttribute(next === 'light' ? 'data-label-dark' : 'data-label-light')
      if (label) toggles[i].setAttribute('aria-label', label)
    }
    try { document.dispatchEvent(new CustomEvent('seasons-theme', { detail: { theme: next } })) } catch (e) {}
  }

  function toggle () {
    var next = current() === 'light' ? 'dark' : 'light'
    write(next === system() ? null : next)
    apply(next)
  }

  apply(read() || system())

  if (light) {
    var onChange = function () { write(null); apply(system()) }
    if (light.addEventListener) light.addEventListener('change', onChange)
    else if (light.addListener) light.addListener(onChange)
  }

  document.addEventListener('click', function (e) {
    var t = e.target && e.target.closest ? e.target.closest('[data-theme-toggle]') : null
    if (t) { e.preventDefault(); toggle() }
  })
  document.addEventListener('DOMContentLoaded', function () { apply(current()) })

  function refresh () { apply(read() || system()) }

  window.seasonsTheme = { current: current, toggle: toggle, system: system, refresh: refresh }
})()
