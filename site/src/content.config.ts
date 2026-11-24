// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The docs collection: Starlight's schema plus Seasons' own frontmatter.
//   art:   a design/art picture for the page's preview card (design/art/<art>.svg)
//   since: the release a page's feature arrived in, shown as a badge
//   audience: who the page is for, shown as a chip and used by the "Start here" role cards
import { defineCollection, z } from 'astro:content'
import { glob } from 'astro/loaders'
import { docsLoader } from '@astrojs/starlight/loaders'
import { docsSchema } from '@astrojs/starlight/schema'

export const collections = {
  // The changelog page renders the repository's CHANGELOG.md as it is.
  changelog: defineCollection({ loader: glob({ pattern: 'CHANGELOG.md', base: '..' }) }),
  docs: defineCollection({
    loader: docsLoader(),
    schema: docsSchema({
      extend: z.object({
        art: z.string().optional(),
        since: z.string().optional(),
        audience: z.enum(['owners', 'admins', 'players', 'everyone']).optional()
      })
    })
  })
}
