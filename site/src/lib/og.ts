// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Where a page's preview image lives: /og/<path>.png, drawn after the build by scripts/og.mjs.
export function ogImagePath (pathname: string): string {
  const clean = pathname.replace(/^\/+|\/+$/g, '')
  return `/og/${clean === '' ? 'index' : clean}.png`
}

/** The section a page belongs to, for its preview card's breadcrumb. */
export function sectionOf (pathname: string): string {
  const parts = pathname.split('/').filter(Boolean)
  if (parts[0] !== 'docs') return ({ download: 'Download', connect: 'Connect', changelog: 'Changelog' } as Record<string, string>)[parts[0] ?? ''] ?? ''
  const SECTIONS: Record<string, string> = { start: 'Start here', owners: 'Running a server', guides: 'Guides', players: 'For players', reference: 'Reference', faq: 'FAQ' }
  return parts[1] ? `Docs · ${SECTIONS[parts[1]] ?? 'Docs'}` : 'Docs'
}
