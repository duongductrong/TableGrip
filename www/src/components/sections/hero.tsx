import { ArrowRight, Download } from 'lucide-react'

import { Section } from '#/components/site/frame'
import { ProductShot, ShotThemeSwitch } from '#/components/site/product-shot'
import { Button } from '#/components/ui/button'
import { links } from '#/content/site'
import { SiteLink } from '#/components/site/site-link'

export function Hero() {
  return (
    <Section rule={false}>
      <div className="px-5 pt-16 pb-10 sm:px-8 sm:pt-24">
        <h1 className="max-w-3xl text-[40px] leading-[1.08] sm:text-[56px]">A fast database client for developers.</h1>
        <p className="mt-5 max-w-2xl text-lg leading-relaxed text-muted-foreground sm:text-xl">
          Write SQL with completion that knows your schema, edit rows in a grid that waits for you to commit, and work
          with PostgreSQL, MySQL, SQL Server, SQLite, MongoDB, Redis{' '}
          <a href="#databases" className="text-foreground underline decoration-rule-strong underline-offset-4 hover:decoration-foreground">
            and more
          </a>
          .
        </p>
      </div>

      <div className="grid border-t border-rule md:grid-cols-2">
        <div className="space-y-3 px-5 py-7 sm:px-8">
          <Button variant="default" size="lg" asChild>
            <a href={links.releases}>
              <Download />
              Download TableGrip
            </a>
          </Button>
          <p className="text-[13px] text-muted-foreground">macOS · Windows · Linux</p>
          <Button variant="arrow" size="sm" asChild>
            <SiteLink href={links.development}>
              Build from source <ArrowRight />
            </SiteLink>
          </Button>
        </div>
        <div className="space-y-2 border-t border-rule px-5 py-7 text-sm leading-relaxed text-muted-foreground sm:px-8 md:border-t-0 md:border-l">
          <p>Free and open source under the Apache License 2.0.</p>
          <p>Built with Kotlin, Compose for Desktop and Jewel, the IntelliJ look and feel.</p>
          <p>No driver ships inside the app: the one you need is downloaded on first connect and checked against a pinned SHA-256.</p>
        </div>
      </div>

      <div className="border-t border-rule bg-surface/60 px-3 pt-6 pb-8 sm:px-8 sm:pt-8 sm:pb-12">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <p className="text-[13px] text-muted-foreground">Screenshots from the app, in the appearance you pick.</p>
          <ShotThemeSwitch />
        </div>
        <ProductShot priority scene="grid" alt="TableGrip showing the orders table of a SQLite database in its data grid" />
      </div>
    </Section>
  )
}
