// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The public site and its docs (ADR 0008, docs/plans/m9-design-overhaul.md). Pages live in
// src/pages, the docs in src/content/docs/docs. design/ is copied into public/design by
// scripts/prepare.mjs before every build; scripts/og.mjs and scripts/csp.mjs run after it.
import starlight from '@astrojs/starlight'
import { defineConfig } from 'astro/config'
import starlightLinksValidator from 'starlight-links-validator'

const REPO = 'https://github.com/gr33nMari0/seasons'

export default defineConfig({
  site: 'https://seasonsmc.petepeterepeat.com',
  trailingSlash: 'always',
  // Every stylesheet and script stays a file of its own, so the CSP needs no 'unsafe-inline'.
  build: { inlineStylesheets: 'never', format: 'directory' },
  vite: { build: { assetsInlineLimit: 0 } },
  devToolbar: { enabled: false },
  integrations: [
    starlight({
      title: 'Seasons',
      description: 'A Paper plugin that runs your Minecraft server in seasons on one world that never resets.',
      favicon: '/favicon.svg',
      social: [{ icon: 'github', label: 'GitHub', href: REPO }],
      editLink: { baseUrl: `${REPO}/edit/main/site/` },
      customCss: ['./src/styles/theme.css'],
      credits: false,
      pagination: true,
      tableOfContents: { minHeadingLevel: 2, maxHeadingLevel: 3 },
      components: {
        Head: './src/components/Head.astro',
        Header: './src/components/Header.astro',
        SiteTitle: './src/components/SiteTitle.astro',
        ThemeProvider: './src/components/ThemeProvider.astro',
        ThemeSelect: './src/components/ThemeSelect.astro',
        MobileMenuFooter: './src/components/MobileMenuFooter.astro',
        PageTitle: './src/components/PageTitle.astro',
        Footer: './src/components/Footer.astro'
      },
      sidebar: [
        { label: 'Start here', items: [{ autogenerate: { directory: 'docs/start' } }] },
        { label: 'Running a server', items: [{ autogenerate: { directory: 'docs/owners' } }] },
        { label: 'Guides', items: [{ autogenerate: { directory: 'docs/guides' } }] },
        { label: 'Addresses and hosting', items: [{ autogenerate: { directory: 'docs/hosting' } }] },
        { label: 'For players', items: [{ autogenerate: { directory: 'docs/players' } }] },
        { label: 'Reference', items: [{ autogenerate: { directory: 'docs/reference' } }, { label: 'Configuration', link: '/docs/reference/config/' }] },
        { label: 'FAQ', link: '/docs/faq/' }
      ],
      plugins: [starlightLinksValidator({ errorOnLocalLinks: false, exclude: ['/', '/download/', '/connect/', '/changelog/', '/connect/**', '/docs/reference/config/', '/docs/reference/config/#*'] })]
    })
  ]
})
