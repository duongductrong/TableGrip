import { ArrowRight } from 'lucide-react'

import { DbMonogram } from '#/components/site/db-tile'
import { Section, SectionHeader } from '#/components/site/frame'
import { Button } from '#/components/ui/button'
import { databaseFamilies, featuredDatabases, links } from '#/content/site'

export function Databases() {
  return (
    <Section id="databases">
      <SectionHeader
        title="Supported databases"
        description="One app for relational, analytical, key-value and document stores, plus the files you keep data in."
        aside={
          <Button variant="arrow" size="sm" asChild>
            <a href={`${links.guide}#drivers`} target="_blank" rel="noreferrer">
              About drivers <ArrowRight />
            </a>
          </Button>
        }
      />
      <ul className="grid grid-cols-2 border-t border-rule sm:grid-cols-4">
        {featuredDatabases.map((db, i) => (
          <li
            key={db.name}
            className={[
              'flex items-center gap-3 border-rule px-5 py-5 text-[15px] font-medium sm:px-8',
              i % 2 === 1 ? 'border-l' : '',
              i >= 2 ? 'border-t sm:border-t-0' : '',
              i % 4 !== 0 ? 'sm:border-l' : 'sm:border-l-0',
              i >= 4 ? 'sm:border-t' : '',
            ].join(' ')}
          >
            <DbMonogram mono={db.mono} color={db.color} />
            {db.name}
          </li>
        ))}
      </ul>
      <dl className="grid border-t border-rule md:grid-cols-3">
        {databaseFamilies.map((f, i) => (
          <div
            key={f.family}
            className={[
              'border-rule px-5 py-6 sm:px-8',
              i > 0 ? 'border-t' : '',
              i < 3 ? 'md:border-t-0' : '',
              i % 3 !== 0 ? 'md:border-l' : '',
            ].join(' ')}
          >
            <dt className="text-sm font-medium">{f.family}</dt>
            <dd className="mt-1.5 text-sm text-foreground/85">{f.engines}</dd>
            <dd className="mt-1 text-[13px] leading-relaxed text-muted-foreground">{f.note}</dd>
          </div>
        ))}
      </dl>
    </Section>
  )
}
