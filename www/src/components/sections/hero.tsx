import { ArrowRight, Download } from 'lucide-react'

import { ProductDemo } from '#/components/site/demo/product-demo'
import { DownloadLink, useDetectedTarget } from '#/components/site/download-dialog'
import { GridPattern, Section } from '#/components/site/frame'
import { GithubIcon } from '#/components/site/github-icon'
import { SiteLink } from '#/components/site/site-link'
import { Button } from '#/components/ui/button'
import { links } from '#/content/site'
import { latestVersion, targetInfo, type DownloadOs } from '#/lib/download'

const osName: Record<DownloadOs, string> = { macos: 'macOS', windows: 'Windows', linux: 'Linux' }

const facts = [
  { label: 'License', value: 'Apache 2.0, free with every feature' },
  { label: 'Built with', value: 'Kotlin, Compose for Desktop and Jewel' },
  { label: 'Drivers', value: 'Fetched on first connect, SHA-256 pinned' },
]

export function Hero() {
  const detected = useDetectedTarget()
  return (
    <Section rule={false}>
      <div className="relative overflow-hidden">
        <GridPattern size={32} className="[mask-image:radial-gradient(ellipse_70%_60%_at_50%_0%,black_10%,transparent_75%)]" />
        <div className="relative flex flex-col items-center px-5 pt-16 pb-14 text-center sm:px-8 sm:pt-24 sm:pb-16">
          {latestVersion && (
            <SiteLink
              href="/changelog"
              className="group inline-flex items-center gap-2 rounded-full border border-rule bg-raised py-1 pr-3 pl-2.5 text-[13px] text-muted-foreground shadow-xs transition-colors hover:border-rule-strong hover:text-foreground"
            >
              <span className="relative flex size-2">
                <span className="absolute inline-flex size-full animate-ping rounded-full bg-brand opacity-50 [animation-duration:2.4s]" />
                <span className="relative inline-flex size-2 rounded-full bg-brand" />
              </span>
              <span className="font-mono text-[12px] text-foreground">v{latestVersion}</span>
              <span aria-hidden className="h-3 w-px bg-rule" />
              What’s new
              <ArrowRight className="size-3.5 transition-transform group-hover:translate-x-0.5" />
            </SiteLink>
          )}

          <h1 className="mt-7 max-w-4xl text-[42px] leading-[1.04] tracking-[-0.035em] sm:text-[64px]">
            A fast database client
            <br />
            <span className="text-muted-foreground">for developers.</span>
          </h1>
          <p className="mt-6 max-w-2xl text-[17px] leading-relaxed text-muted-foreground sm:text-lg">
            Write SQL with completion that knows your schema, edit rows in a grid that waits for you to commit, and work
            with PostgreSQL, MySQL, SQL Server, SQLite, MongoDB, Redis{' '}
            <a href="#databases" className="text-foreground underline decoration-rule-strong underline-offset-4 hover:decoration-foreground">
              and more
            </a>
            .
          </p>

          <div className="mt-9 flex flex-col items-center gap-3 sm:flex-row">
            <Button size="lg" className="min-w-52" asChild>
              <DownloadLink>
                <Download />
                {detected ? `Download for ${osName[targetInfo[detected].os]}` : 'Download TableGrip'}
              </DownloadLink>
            </Button>
            <Button variant="outline" size="lg" asChild>
              <a href={links.github} target="_blank" rel="noreferrer">
                <GithubIcon className="size-4" />
                View on GitHub
              </a>
            </Button>
          </div>
          <p className="mt-5 font-mono text-[12px] text-muted-foreground">macOS · Windows · Linux</p>
        </div>
      </div>

      <ProductDemo className="border-t border-rule" />

      <dl className="grid border-t border-rule md:grid-cols-3">
        {facts.map((fact, i) => (
          <div key={fact.label} className={['border-rule px-5 py-5 sm:px-8', i > 0 ? 'border-t md:border-t-0 md:border-l' : ''].join(' ')}>
            <dt className="font-mono text-[11px] tracking-wide text-muted-foreground uppercase">{fact.label}</dt>
            <dd className="mt-1.5 text-sm">{fact.value}</dd>
          </div>
        ))}
      </dl>
    </Section>
  )
}
