import { ArrowRight, Sparkles } from 'lucide-react'

import { Section } from '#/components/site/frame'
import { ProductShot, ShotThemeSwitch } from '#/components/site/product-shot'
import { Badge } from '#/components/ui/badge'
import { Button } from '#/components/ui/button'
import { agentClients, agentPoints, links } from '#/content/site'
import { SiteLink } from '#/components/site/site-link'

export function Agent() {
  return (
    <Section id="agent">
      <div className="flex flex-col gap-6 px-5 pt-14 pb-8 sm:px-8 md:flex-row md:items-end md:justify-between">
        <div className="max-w-2xl">
          <Badge
            variant="outline"
            className="mb-4 h-6 gap-1.5 rounded-full border-rule bg-raised px-2.5 text-xs font-medium text-brand-text"
          >
            <Sparkles className="size-3!" />
            AI agent
          </Badge>
          <h2 className="text-[28px] leading-tight sm:text-3xl">Ask in plain words. Keep your hands on the wheel.</h2>
          <p className="mt-3 text-lg leading-relaxed text-muted-foreground">
            The chat sits beside your console. The agent reads your schema, writes the SQL and explains it. TableGrip
            runs a query only when you say so, and warns you before anything that writes.
          </p>
        </div>
        <ShotThemeSwitch className="shrink-0" />
      </div>

      <div className="border-t border-rule bg-surface/60 px-3 py-6 sm:px-8 sm:py-10">
        <ProductShot
          scene="agent"
          alt="TableGrip's agent chat beside a SQL console: the agent answers a question about lost customers with a CREATE VIEW statement, flagged because it changes the schema"
        />
      </div>

      <div className="grid border-t border-rule sm:grid-cols-2 lg:grid-cols-4">
        {agentPoints.map((point, i) => (
          <div
            key={point.title}
            className={[
              'border-rule px-5 py-7 sm:px-8',
              i > 0 ? 'border-t sm:border-t-0' : '',
              i % 2 === 1 ? 'sm:border-l' : '',
              i >= 2 ? 'sm:border-t lg:border-t-0' : '',
              i > 0 ? 'lg:border-l' : '',
            ].join(' ')}
          >
            <h3 className="text-base">{point.title}</h3>
            <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{point.body}</p>
          </div>
        ))}
      </div>

      <div className="flex flex-col gap-4 border-t border-rule px-5 py-6 sm:px-8 md:flex-row md:items-center md:justify-between">
        <div className="flex flex-wrap items-center gap-2">
          <span className="mr-1 text-[13px] text-muted-foreground">Works with</span>
          {agentClients.map((client) => (
            <Badge key={client} variant="outline" className="h-7 rounded-md border-rule bg-raised px-2.5 text-[13px] font-normal">
              {client}
            </Badge>
          ))}
          <span className="text-[13px] text-muted-foreground">or any Anthropic or OpenAI-compatible API</span>
        </div>
        <Button variant="arrow" size="sm" asChild>
          <SiteLink href={links.agentDocs}>
            Agent in the guide <ArrowRight />
          </SiteLink>
        </Button>
      </div>
    </Section>
  )
}
