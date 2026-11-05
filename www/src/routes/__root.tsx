import { HeadContent, Scripts, createRootRoute } from '@tanstack/react-router'

import { GridBackdrop } from '#/components/site/grid-backdrop'
import { SiteFooter } from '#/components/site/site-footer'
import { SiteHeader } from '#/components/site/site-header'
import { TooltipProvider } from '#/components/ui/tooltip'
import { ThemeProvider, themeScript } from '#/lib/theme'
import appCss from '../styles.css?url'

const title = 'TableGrip: a fast desktop database client'
const description =
  'Write SQL with schema-aware completion, edit data in a transactional grid, and manage PostgreSQL, MySQL, SQL Server, SQLite, MongoDB, Redis and more. Free and open source.'

export const Route = createRootRoute({
  head: () => ({
    meta: [
      { charSet: 'utf-8' },
      { name: 'viewport', content: 'width=device-width, initial-scale=1' },
      { title },
      { name: 'description', content: description },
      { name: 'theme-color', content: '#ffffff', media: '(prefers-color-scheme: light)' },
      { name: 'theme-color', content: '#1c1c1c', media: '(prefers-color-scheme: dark)' },
      { property: 'og:title', content: title },
      { property: 'og:description', content: description },
      { property: 'og:image', content: '/screenshots/grid-dark.webp' },
      { name: 'twitter:card', content: 'summary_large_image' },
    ],
    links: [
      { rel: 'stylesheet', href: appCss },
      { rel: 'icon', type: 'image/png', href: '/logo.png' },
      { rel: 'preload', as: 'image', href: '/screenshots/grid-light.webp', media: '(prefers-color-scheme: light)' },
      { rel: 'preload', as: 'image', href: '/screenshots/grid-dark.webp', media: '(prefers-color-scheme: dark)' },
    ],
    scripts: [{ children: themeScript }],
  }),
  shellComponent: RootDocument,
})

function RootDocument({ children }: { children: React.ReactNode }) {
  return (
    // The head script sets the theme class before React hydrates, so the attributes differ on purpose.
    <html lang="en" suppressHydrationWarning>
      <head>
        <HeadContent />
      </head>
      <body>
        <ThemeProvider>
          <TooltipProvider delayDuration={300}>
            <div className="relative isolate">
              <GridBackdrop />
              <SiteHeader />
              <main>{children}</main>
              <SiteFooter />
            </div>
          </TooltipProvider>
        </ThemeProvider>
        <Scripts />
      </body>
    </html>
  )
}
