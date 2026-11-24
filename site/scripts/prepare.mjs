// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Before every build or dev server: copies the shared design (../design) into public/design, and
// the icons into public/, so the site serves the same files Seasons Web bundles. Never edit the
// copies; they are rebuilt each time and ignored by git.
import { cpSync, mkdirSync, rmSync } from 'node:fs'
import { join } from 'node:path'

const SITE = join(import.meta.dirname, '..')
const DESIGN = join(SITE, '../design')
const PUBLIC = join(SITE, 'public')

rmSync(join(PUBLIC, 'design'), { recursive: true, force: true })
mkdirSync(join(PUBLIC, 'design'), { recursive: true })
for (const name of ['tokens.css', 'fonts.css', 'theme.js', 'icons.svg', 'fonts', 'art']) {
  cpSync(join(DESIGN, name), join(PUBLIC, 'design', name), { recursive: true })
}
cpSync(join(DESIGN, 'art/logo-tile.svg'), join(PUBLIC, 'favicon.svg'))
cpSync(join(SITE, '../docs/images/icon.png'), join(PUBLIC, 'icon-512.png'))
cpSync(join(SITE, '../docs/images/icon.png'), join(PUBLIC, 'apple-touch-icon.png'))
console.log('design copied into public/')
