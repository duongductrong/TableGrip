import { Outlet, createFileRoute } from '@tanstack/react-router'

import { DocsMobileNav, DocsSidebar } from '#/components/docs/docs-sidebar'
import { Frame } from '#/components/site/frame'

export const Route = createFileRoute('/docs')({ component: DocsLayout })

function DocsLayout() {
  return (
    <Frame className="bg-background">
      <DocsMobileNav />
      <div className="flex">
        <DocsSidebar />
        <Outlet />
      </div>
    </Frame>
  )
}
