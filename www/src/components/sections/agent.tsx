import { ArrowRight } from 'lucide-react'

import { Section, SectionHeader, Stage } from '#/components/site/frame'
import { ProductShot } from '#/components/site/product-shot'
import { SiteLink } from '#/components/site/site-link'
import { Button } from '#/components/ui/button'
import { agentClients, agentPoints, links } from '#/content/site'
import { cn } from '#/lib/utils'

export function Agent() {
  return (
    <Section id="agent">
      <SectionHeader
        index="02"
        eyebrow="AI agent"
        title="Ask in plain words. Keep your hands on the wheel."
        description="The chat sits beside your console. The agent reads your schema, writes the SQL and explains it. TableGrip runs a query only when you say so, and warns you before anything that writes."
        aside={
          <Button variant="arrow" size="sm" asChild>
            <SiteLink href={links.agentDocs}>
              Agent in the guide <ArrowRight />
            </SiteLink>
          </Button>
        }
      />

      <Stage className="border-t border-rule px-3 py-6 sm:px-10 sm:py-10">
        <ProductShot
          scene="agent"
          alt="TableGrip's agent chat beside a SQL console: the agent answers a question about lost customers with a CREATE VIEW statement, flagged because it changes the schema"
        />
      </Stage>

      <ol className="grid border-t border-rule sm:grid-cols-2 lg:grid-cols-4">
        {agentPoints.map((point, i) => (
          <li
            key={point.title}
            className={cn(
              'border-rule px-5 py-7 sm:px-8',
              i > 0 && 'border-t sm:border-t-0',
              i % 2 === 1 && 'sm:border-l',
              i >= 2 && 'sm:border-t lg:border-t-0',
              i > 0 && 'lg:border-l',
            )}
          >
            <p className="font-mono text-[11px] tracking-wide text-muted-foreground uppercase">{String(i + 1).padStart(2, '0')}</p>
            <h3 className="mt-1.5 text-base">{point.title}</h3>
            <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{point.body}</p>
          </li>
        ))}
      </ol>

      <div className="flex flex-wrap items-center gap-2 border-t border-rule px-5 py-5 sm:px-8">
        <span className="mr-2 font-mono text-[11px] tracking-wide text-muted-foreground uppercase">Works with</span>
        {agentClients.map((client) => (
          <span key={client} className="rounded-md border border-rule bg-raised px-2.5 py-1 text-[13px] shadow-xs">
            {client}
          </span>
        ))}
        <span className="ml-1 text-[13px] text-muted-foreground">or any Anthropic or OpenAI-compatible API</span>
      </div>
    </Section>
  )
}
