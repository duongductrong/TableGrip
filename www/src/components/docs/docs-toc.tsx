import * as React from 'react'

import type { TocItem } from '#/lib/docs'
import { cn } from '#/lib/utils'

/** "On this page": the page's h2 and h3 headings, with the one being read highlighted. */
export function DocsToc({ items }: { items: TocItem[] }) {
  const [active, setActive] = React.useState<string | null>(items[0]?.id ?? null)

  React.useEffect(() => {
    const headings = items.map((i) => document.getElementById(i.id)).filter((el): el is HTMLElement => !!el)
    if (!headings.length) return
    const onScroll = () => {
      // The last heading above the top quarter of the viewport is the section being read.
      const line = window.innerHeight * 0.25
      let current = headings[0].id
      for (const h of headings) if (h.getBoundingClientRect().top <= line) current = h.id
      setActive(current)
    }
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [items])

  if (items.length < 2) return null
  return (
    <nav aria-label="On this page" className="text-[13px]">
      <p className="mb-3 font-medium">On this page</p>
      <ul className="space-y-2 border-l border-rule">
        {items.map((item) => (
          <li key={item.id}>
            <a
              href={`#${item.id}`}
              className={cn(
                '-ml-px block border-l py-0.5 leading-snug transition-colors',
                item.depth === 3 ? 'pl-6' : 'pl-3',
                active === item.id
                  ? 'border-foreground text-foreground'
                  : 'border-transparent text-muted-foreground hover:text-foreground',
              )}
            >
              {item.text}
            </a>
          </li>
        ))}
      </ul>
    </nav>
  )
}
