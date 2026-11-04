import { ArrowUpRight, Heart } from 'lucide-react'

import { Section, SectionHeader } from '#/components/site/frame'
import { GithubIcon } from '#/components/site/github-icon'
import { Button } from '#/components/ui/button'
import { links, platforms } from '#/content/site'

export function DownloadSection() {
  return (
    <Section id="download">
      <SectionHeader
        title="Download TableGrip"
        description="Free, with every feature. Installers for each platform are on the releases page."
      />
      <div className="grid border-t border-rule md:grid-cols-3">
        {platforms.map((p, i) => (
          <a
            key={p.os}
            href={links.releases}
            className={[
              'group flex items-center justify-between gap-4 border-rule px-5 py-6 transition-colors hover:bg-surface sm:px-8',
              i > 0 ? 'border-t md:border-t-0 md:border-l' : '',
            ].join(' ')}
          >
            <div>
              <div className="font-medium">
                {p.os} <span className="font-mono text-sm text-muted-foreground">{p.pkg}</span>
              </div>
              <div className="mt-1 text-[13px] text-muted-foreground">{p.note}</div>
            </div>
            <ArrowUpRight className="size-4 text-muted-foreground transition-transform group-hover:translate-x-0.5 group-hover:-translate-y-0.5 group-hover:text-foreground" />
          </a>
        ))}
      </div>
      <div className="grid border-t border-rule md:grid-cols-2">
        <div className="px-5 py-8 sm:px-8">
          <h3 className="text-lg">Open source</h3>
          <p className="mt-2 text-sm leading-relaxed text-muted-foreground">
            TableGrip is released under the Apache License 2.0. Bug reports, ideas and pull requests are welcome.
          </p>
          <Button variant="outline" size="sm" className="mt-4" asChild>
            <a href={links.github} target="_blank" rel="noreferrer">
              <GithubIcon className="size-4" />
              View on GitHub
            </a>
          </Button>
        </div>
        <div className="border-t border-rule px-5 py-8 sm:px-8 md:border-t-0 md:border-l">
          <h3 className="text-lg">Sponsor</h3>
          <p className="mt-2 text-sm leading-relaxed text-muted-foreground">
            If TableGrip saves you time, you can support its development.
          </p>
          <div className="mt-4 flex flex-wrap gap-2">
            <Button variant="outline" size="sm" asChild>
              <a href={links.sponsors} target="_blank" rel="noreferrer">
                <Heart className="text-pink-500" />
                GitHub Sponsors
              </a>
            </Button>
            <Button variant="ghost" size="sm" asChild>
              <a href={links.kofi} target="_blank" rel="noreferrer">Ko-fi</a>
            </Button>
            <Button variant="ghost" size="sm" asChild>
              <a href={links.paypal} target="_blank" rel="noreferrer">PayPal</a>
            </Button>
          </div>
        </div>
      </div>
    </Section>
  )
}
