import * as React from 'react'
import { ArrowRight, Check, Copy, ShieldAlert, X } from 'lucide-react'

import { Section, SectionHeader, Stage } from '#/components/site/frame'
import { SiteLink } from '#/components/site/site-link'
import { Button } from '#/components/ui/button'
import { links, mcpClients, mcpCommand, mcpPoints, mcpSteps } from '#/content/site'
import { cn } from '#/lib/utils'

export function Mcp() {
  return (
    <Section id="mcp">
      <SectionHeader
        index="03"
        eyebrow="MCP server"
        title="Your connections, in any AI app."
        description="TableGrip is also an MCP server. Claude, Cursor and VS Code can read your schemas and run queries through the connections you already saved, with the passwords, tunnels and safety rules you already set."
        aside={
          <Button variant="arrow" size="sm" asChild>
            <SiteLink href={links.mcpDocs}>
              Set up MCP <ArrowRight />
            </SiteLink>
          </Button>
        }
      />

      <div className="grid border-t border-rule lg:grid-cols-5">
        <div className="flex flex-col gap-8 px-5 py-10 sm:px-8 lg:col-span-2">
          <ol className="flex flex-col gap-5">
            {mcpSteps.map((step, i) => (
              <li key={step.title} className="flex gap-4">
                <span className="flex size-7 shrink-0 items-center justify-center rounded-full border border-rule-strong bg-raised font-mono text-[11px] shadow-xs">
                  {i + 1}
                </span>
                <div>
                  <h3 className="text-base">{step.title}</h3>
                  <p className="mt-0.5 text-sm leading-relaxed text-muted-foreground">{step.body}</p>
                </div>
              </li>
            ))}
          </ol>

          <div>
            <p className="mb-2 font-mono text-[11px] tracking-wide text-muted-foreground uppercase">Or in a terminal</p>
            <CommandBlock command={mcpCommand} />
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <span className="mr-1 font-mono text-[11px] tracking-wide text-muted-foreground uppercase">Works with</span>
            {mcpClients.map((client) => (
              <span key={client} className="rounded-md border border-rule bg-raised px-2.5 py-1 text-[13px] shadow-xs">
                {client}
              </span>
            ))}
            <span className="text-[13px] text-muted-foreground">and any MCP client</span>
          </div>
        </div>

        <Stage className="border-t border-rule px-3 py-8 sm:px-8 sm:py-10 lg:col-span-3 lg:border-t-0 lg:border-l">
          <Transcript />
        </Stage>
      </div>

      <ol className="grid border-t border-rule sm:grid-cols-2 lg:grid-cols-4">
        {mcpPoints.map((point, i) => (
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
    </Section>
  )
}

function CommandBlock({ command }: { command: string }) {
  const [copied, setCopied] = React.useState(false)
  return (
    <div className="flex items-start gap-2 rounded-lg border border-rule-strong bg-raised py-2.5 pr-2 pl-3.5 shadow-xs">
      <code className="min-w-0 flex-1 font-mono text-[12.5px] leading-relaxed break-words">
        <span aria-hidden className="text-muted-foreground select-none">$ </span>
        {command}
      </code>
      <button
        type="button"
        aria-label={copied ? 'Copied' : 'Copy command'}
        onClick={() => {
          void navigator.clipboard?.writeText(command).then(() => {
            setCopied(true)
            window.setTimeout(() => setCopied(false), 1500)
          })
        }}
        className="inline-flex size-7 shrink-0 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-surface-strong hover:text-foreground"
      >
        {copied ? <Check className="size-3.5" /> : <Copy className="size-3.5" />}
      </button>
    </div>
  )
}

/** What an AI app shows while it works through TableGrip: a read that runs, a write that waits, a trick that doesn't. */
function Transcript() {
  return (
    <div
      role="img"
      aria-label="An AI app calling TableGrip's MCP tools: a query on shop-dev returns rows, a DELETE on a production connection waits for the user's approval, and a smuggled DROP TABLE is refused"
      className="mx-auto flex max-w-xl flex-col gap-3"
    >
      <Bubble>Which tiers do our customers fall into?</Bubble>

      <ToolCall name="query" connection="shop-dev" sql="SELECT tier, count(*) AS customers FROM customers GROUP BY tier">
        <table className="w-full font-mono text-[11.5px]">
          <thead>
            <tr className="text-left text-muted-foreground">
              <th className="py-1 pr-4 font-normal">tier</th>
              <th className="py-1 font-normal">customers</th>
            </tr>
          </thead>
          <tbody>
            {[
              ['basic', '1,204'],
              ['gold', '311'],
              ['platinum', '42'],
            ].map(([tier, count]) => (
              <tr key={tier} className="border-t border-rule">
                <td className="py-1 pr-4">{tier}</td>
                <td className="py-1 tabular-nums">{count}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </ToolCall>

      <ToolCall name="execute" connection="billing" production sql="DELETE FROM invoices WHERE status = 'void'">
        <div className="flex flex-wrap items-center gap-2 text-[12.5px]">
          <ShieldAlert className="size-4 text-warning" strokeWidth={1.75} />
          <span className="font-medium">Run this on billing (Production)?</span>
          <span className="ml-auto flex gap-1.5">
            <span className="rounded-md bg-brand px-2.5 py-1 text-[12px] font-medium text-brand-foreground">Run it</span>
            <span className="rounded-md border border-rule-strong px-2.5 py-1 text-[12px]">Decline</span>
          </span>
        </div>
      </ToolCall>

      <ToolCall name="query" connection="shop-dev" sql="SELECT 1; DROP TABLE users" refused>
        <p className="flex items-center gap-2 text-[12.5px] text-muted-foreground">
          <X className="size-4 text-danger" strokeWidth={1.75} />
          Send one statement per call; this has 2.
        </p>
      </ToolCall>
    </div>
  )
}

function Bubble({ children }: { children: React.ReactNode }) {
  return (
    <p className="ml-auto max-w-[80%] rounded-2xl rounded-br-md bg-foreground px-3.5 py-2 text-[13.5px] text-background shadow-sm">{children}</p>
  )
}

function ToolCall({
  name,
  connection,
  sql,
  production,
  refused,
  children,
}: {
  name: string
  connection: string
  sql: string
  production?: boolean
  refused?: boolean
  children: React.ReactNode
}) {
  return (
    <div className="overflow-hidden rounded-xl border border-rule-strong bg-raised shadow-[var(--float-shadow)]">
      <div className="flex items-center gap-2 border-b border-rule bg-surface px-3.5 py-2 font-mono text-[11.5px]">
        <span className="text-muted-foreground">tablegrip</span>
        <span className="text-muted-foreground">›</span>
        <span className="font-medium">{name}</span>
        <span className="ml-auto flex items-center gap-1.5 text-muted-foreground">
          {connection}
          {production && <span className="rounded bg-danger px-1 py-px text-[9.5px] font-semibold text-white">PROD</span>}
          {refused && <span className="rounded border border-rule-strong px-1 py-px text-[9.5px] font-semibold">REFUSED</span>}
        </span>
      </div>
      <div className="flex flex-col gap-2.5 px-3.5 py-3">
        <code className={cn('font-mono text-[12px] leading-relaxed break-words', refused && 'text-muted-foreground line-through decoration-danger/60')}>{sql}</code>
        {children}
      </div>
    </div>
  )
}
