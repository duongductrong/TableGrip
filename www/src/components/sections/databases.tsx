import { ArrowRight } from 'lucide-react'

import { Section, SectionHeader } from '#/components/site/frame'
import { SiteLink } from '#/components/site/site-link'
import { Button } from '#/components/ui/button'
import { dbLogos } from '#/content/db-logos'
import { databaseFamilies, featuredDatabases, links, supportedCounts } from '#/content/site'
import { cn } from '#/lib/utils'

const stats = [
  { value: supportedCounts.databases, label: 'Databases built in' },
  { value: supportedCounts.files, label: 'Data file formats' },
  { value: supportedCounts.families, label: 'Database families' },
  { value: '+ JDBC', label: 'Any other driver you bring' },
]

export function Databases() {
  return (
    <Section id="databases">
      <SectionHeader
        index="04"
        eyebrow="Databases"
        title="One client for every store you run."
        description={`${supportedCounts.databases} databases out of the box, relational, analytical, key-value and document, plus ${supportedCounts.files} file formats you keep data in. Anything else with a JDBC driver works too.`}
        aside={
          <Button variant="arrow" size="sm" asChild>
            <SiteLink href={links.drivers}>
              About drivers <ArrowRight />
            </SiteLink>
          </Button>
        }
      />

      <dl className="grid grid-cols-2 border-t border-rule sm:grid-cols-4">
        {stats.map((s, i) => (
          <div
            key={s.label}
            className={cn('border-rule px-5 py-6 sm:px-8', i % 2 === 1 && 'border-l', i >= 2 && 'border-t sm:border-t-0', i % 4 !== 0 ? 'sm:border-l' : 'sm:border-l-0')}
          >
            <dd className="text-[34px] leading-none font-semibold tracking-[-0.03em] tabular-nums sm:text-[40px]">{s.value}</dd>
            <dt className="mt-2 font-mono text-[11px] tracking-wide text-muted-foreground uppercase">{s.label}</dt>
          </div>
        ))}
      </dl>

      <ul className="grid grid-cols-2 border-t border-rule sm:grid-cols-4">
        {featuredDatabases.map((db, i) => (
          <li
            key={db.name}
            className={cn(
              'group flex items-center gap-3 border-rule px-5 py-6 text-[15px] font-medium transition-colors hover:bg-surface sm:px-8',
              i % 2 === 1 && 'border-l',
              i >= 2 && 'border-t sm:border-t-0',
              i % 4 !== 0 ? 'sm:border-l' : 'sm:border-l-0',
              i >= 4 && 'sm:border-t',
            )}
          >
            <span
              aria-hidden
              className={cn(
                'inline-flex size-10 shrink-0 items-center justify-center rounded-lg border border-rule bg-raised text-foreground/75 shadow-xs transition-colors group-hover:text-foreground',
                // Marks too pale for a light page sit on black, as their owners publish them.
                dbLogos[db.name]?.light && 'border-black bg-black dark:border-rule dark:bg-raised',
              )}
            >
              {dbLogos[db.name] ? (
                <svg viewBox="0 0 24 24" className="size-5">
                  {/* The official colour; marks too dark for a dark page are drawn white there. */}
                  <path d={dbLogos[db.name].path} fill={dbLogos[db.name].hex} className={cn(dbLogos[db.name].dark && 'dark:fill-white')} />
                </svg>
              ) : (
                <span className="font-mono text-[10.5px] font-semibold">{db.mono}</span>
              )}
            </span>
            {db.name}
          </li>
        ))}
      </ul>

      <dl className="grid border-t border-rule md:grid-cols-3">
        {databaseFamilies.map((f, i) => (
          <div
            key={f.family}
            className={cn('border-rule px-5 py-6 sm:px-8', i > 0 && 'border-t', i < 3 && 'md:border-t-0', i % 3 !== 0 && 'md:border-l')}
          >
            <dt className="flex items-baseline justify-between gap-3 font-mono text-[11px] tracking-wide text-muted-foreground uppercase">
              {f.family}
              {f.family !== 'Anything else' && <span className="tabular-nums">{f.engines.length}</span>}
            </dt>
            <dd className="mt-2 text-sm font-medium">{f.engines.join(' · ')}</dd>
            <dd className="mt-1 text-[13px] leading-relaxed text-muted-foreground">{f.note}</dd>
          </div>
        ))}
      </dl>
    </Section>
  )
}
