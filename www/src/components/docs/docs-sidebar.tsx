import * as React from 'react'
import { Link, useLocation } from '@tanstack/react-router'
import { ChevronDown } from 'lucide-react'

import { docsNav } from '#/content/docs-nav'
import { docs } from '#/lib/docs'
import { cn } from '#/lib/utils'

const titles = new Map(docs.map((d) => [d.slug, d.nav]))

function NavList({ current, onNavigate }: { current: string; onNavigate?: () => void }) {
  return (
    <nav aria-label="Documentation" className="space-y-7">
      {docsNav.map((group) => (
        <div key={group.title}>
          <p className="mb-3 text-[13px] font-medium text-muted-foreground">{group.title}</p>
          <ul className="space-y-2">
            {group.items.map((slug) => {
              const active = slug === current
              return (
                <li key={slug}>
                  <Link
                    to="/docs/$slug"
                    params={{ slug }}
                    onClick={onNavigate}
                    aria-current={active ? 'page' : undefined}
                    className={cn(
                      'text-sm leading-snug underline underline-offset-[5px] transition-colors',
                      active
                        ? 'font-medium text-foreground decoration-foreground decoration-[1.5px]'
                        : 'text-foreground/80 decoration-rule hover:text-foreground hover:decoration-rule-strong',
                    )}
                  >
                    {titles.get(slug)}
                  </Link>
                </li>
              )
            })}
          </ul>
        </div>
      ))}
    </nav>
  )
}

function useCurrentSlug() {
  const pathname = useLocation({ select: (l) => l.pathname })
  return pathname.replace(/^\/docs\/?/, '').replace(/\/$/, '') || 'overview'
}

/** The docs sidebar: every page, grouped. Sticky on wide screens. */
export function DocsSidebar() {
  const current = useCurrentSlug()
  return (
    <aside className="hidden w-60 shrink-0 border-r border-rule lg:block">
      <div className="sticky top-14 max-h-[calc(100dvh-3.5rem)] overflow-y-auto px-6 py-10">
        <NavList current={current} />
      </div>
    </aside>
  )
}

/** On narrow screens the sidebar folds into a disclosure above the page. */
export function DocsMobileNav() {
  const current = useCurrentSlug()
  const [open, setOpen] = React.useState(false)
  const page = docs.find((d) => d.slug === current)
  return (
    <div className="border-b border-rule lg:hidden">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        className="flex w-full items-center justify-between gap-3 px-5 py-3 text-left text-sm sm:px-8"
      >
        <span>
          <span className="text-muted-foreground">{page?.group ?? 'Docs'} / </span>
          <span className="font-medium">{page?.title ?? 'Documentation'}</span>
        </span>
        <ChevronDown className={cn('size-4 text-muted-foreground transition-transform', open && 'rotate-180')} />
      </button>
      {open && (
        <div className="border-t border-rule px-5 py-6 sm:px-8">
          <NavList current={current} onNavigate={() => setOpen(false)} />
        </div>
      )}
    </div>
  )
}
