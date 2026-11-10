import * as React from 'react'
import { Activity, ArrowRight, Check, Command, FileSpreadsheet, ShieldCheck } from 'lucide-react'

import { Section, SectionHeader, Stage } from '#/components/site/frame'
import { ProductShot } from '#/components/site/product-shot'
import { SiteLink } from '#/components/site/site-link'
import { Button } from '#/components/ui/button'
import { features, highlights } from '#/content/site'
import { cn } from '#/lib/utils'

const highlightIcons = [ShieldCheck, Activity, FileSpreadsheet, Command]

/**
 * The three core features as tabs over one large screenshot. Each tab keeps its feature's id, so the header's
 * Features menu (/#editor, /#grid, /#schema) still lands here and opens the right one.
 */
export function Features() {
  const [active, setActive] = React.useState(features[0].id)
  const feature = features.find((f) => f.id === active) ?? features[0]

  React.useEffect(() => {
    const sync = () => {
      const id = window.location.hash.slice(1)
      if (features.some((f) => f.id === id)) setActive(id)
    }
    sync()
    window.addEventListener('hashchange', sync)
    return () => window.removeEventListener('hashchange', sync)
  }, [])

  return (
    <Section id="features">
      <SectionHeader
        index="01"
        eyebrow="Features"
        title="Query, look, change. Without leaving the keyboard."
        description="The everyday loop of working with a database, built so the next step is always one shortcut away."
      />

      <div role="tablist" aria-label="Features" className="grid border-t border-rule md:grid-cols-3">
        {features.map((f, i) => {
          const selected = f.id === active
          return (
            <button
              key={f.id}
              id={f.id}
              type="button"
              role="tab"
              aria-selected={selected}
              aria-controls="feature-panel"
              onClick={() => setActive(f.id)}
              className={cn(
                'group relative border-rule px-5 py-6 text-left transition-colors outline-none focus-visible:bg-surface sm:px-8',
                i > 0 && 'border-t md:border-t-0 md:border-l',
                selected ? 'bg-raised' : 'hover:bg-surface',
              )}
            >
              <span
                aria-hidden
                className={cn(
                  'absolute inset-x-0 top-0 h-0.5 bg-foreground transition-opacity',
                  selected ? 'opacity-100' : 'opacity-0',
                )}
              />
              <span className="font-mono text-[11px] tracking-wide text-muted-foreground uppercase">
                {String(i + 1).padStart(2, '0')}
              </span>
              <span
                className={cn(
                  'mt-1.5 block text-[17px] font-semibold tracking-tight transition-colors',
                  selected ? 'text-foreground' : 'text-muted-foreground group-hover:text-foreground',
                )}
              >
                {f.title}
              </span>
              <span className="mt-1.5 block text-sm leading-relaxed text-muted-foreground">{f.body}</span>
            </button>
          )
        })}
      </div>

      <div id="feature-panel" role="tabpanel" aria-labelledby={feature.id}>
        <Stage className="border-t border-rule px-3 py-6 sm:px-10 sm:py-10">
          {/* Every shot sits in the same cell so they load together and the tabs cross-fade without a blank frame. */}
          <div className="grid items-center">
            {features.map((f) => (
              <ProductShot
                key={f.id}
                scene={f.scene}
                alt={f.alt}
                className={cn(
                  'col-start-1 row-start-1 transition-[opacity,visibility] duration-300',
                  f.id === active ? 'visible opacity-100' : 'invisible opacity-0',
                )}
              />
            ))}
          </div>
        </Stage>
        <div className="flex flex-col gap-4 border-t border-rule px-5 py-5 sm:px-8 md:flex-row md:items-center md:justify-between">
          <ul className="flex flex-wrap gap-x-6 gap-y-2 text-sm">
            {feature.points.map((point) => (
              <li key={point} className="flex items-center gap-2">
                <Check className="size-3.5 shrink-0 text-muted-foreground" />
                {point}
              </li>
            ))}
          </ul>
          <Button variant="arrow" size="sm" className="self-start md:self-auto" asChild>
            <SiteLink href={feature.docs}>
              Read the guide <ArrowRight />
            </SiteLink>
          </Button>
        </div>
      </div>

      <div className="grid border-t border-rule sm:grid-cols-2 lg:grid-cols-4">
        {highlights.map((h, i) => {
          const Icon = highlightIcons[i]
          return (
            <div
              key={h.title}
              className={cn(
                'border-rule px-5 py-8 sm:px-8',
                i > 0 && 'border-t sm:border-t-0',
                i % 2 === 1 && 'sm:border-l',
                i >= 2 && 'sm:border-t lg:border-t-0',
                i > 0 && 'lg:border-l',
              )}
            >
              <span className="flex size-9 items-center justify-center rounded-lg border border-rule bg-raised shadow-xs">
                <Icon className="size-4" strokeWidth={1.75} />
              </span>
              <h3 className="mt-5 text-base">{h.title}</h3>
              <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{h.body}</p>
            </div>
          )
        })}
      </div>
    </Section>
  )
}
