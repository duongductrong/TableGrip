import { ArrowDown, Heart } from 'lucide-react'

import { DownloadLink } from '#/components/site/download-dialog'
import { GridPattern, Section } from '#/components/site/frame'
import { GithubIcon } from '#/components/site/github-icon'
import { Button } from '#/components/ui/button'
import { links, platforms } from '#/content/site'
import { defaultTarget, downloadSize, fileName, latestVersion } from '#/lib/download'
import { cn } from '#/lib/utils'

export function DownloadSection() {
  return (
    <Section id="download">
      <div className="relative overflow-hidden">
        <GridPattern size={32} />
        <div className="relative flex flex-col items-center px-5 pt-20 pb-14 text-center sm:px-8 sm:pt-24">
          {latestVersion && (
            <p className="flex items-center gap-2 text-[13px] text-muted-foreground">
              <span className="size-2 rounded-full bg-success" />
              Latest release <span className="font-mono text-[12px] text-foreground">v{latestVersion}</span>
            </p>
          )}
          <img src="/logo.png" alt="" width={72} height={72} className="mt-6 size-[72px] rounded-[18px] shadow-lg" />
          <h2 className="mt-6 text-[34px] leading-[1.1] sm:text-[44px]">Install TableGrip</h2>
          <p className="mt-4 max-w-xl text-[17px] leading-relaxed text-muted-foreground">
            Free, with every feature. Pick your system and the installer downloads right away, with a short guide for
            the rest.
          </p>
        </div>
      </div>

      <div className="grid border-t border-rule md:grid-cols-3">
        {platforms.map((p, i) => {
          const target = defaultTarget(p.id)
          const size = downloadSize(target)
          return (
            <DownloadLink
              key={p.os}
              os={p.id}
              className={cn(
                'group flex items-center justify-between gap-4 border-rule px-5 py-6 transition-colors hover:bg-surface sm:px-8',
                i > 0 && 'border-t md:border-t-0 md:border-l',
              )}
            >
              <div className="min-w-0">
                <div className="text-[17px] font-semibold tracking-tight">{p.os}</div>
                <div className="mt-1 truncate font-mono text-[12px] text-muted-foreground" title={fileName(target)}>
                  {p.pkg}
                  {size ? ` · ${size} MB` : ''}
                </div>
                <div className="mt-0.5 text-[13px] text-muted-foreground">{p.note}</div>
              </div>
              <span className="flex size-9 shrink-0 items-center justify-center rounded-full border border-rule bg-raised shadow-xs transition-colors group-hover:border-foreground group-hover:bg-foreground group-hover:text-background">
                <ArrowDown className="size-4" />
              </span>
            </DownloadLink>
          )
        })}
      </div>

      <div className="grid border-t border-rule md:grid-cols-2">
        <div className="px-5 py-8 sm:px-8">
          <p className="font-mono text-[11px] tracking-wide text-muted-foreground uppercase">Open source</p>
          <p className="mt-2 text-sm leading-relaxed text-muted-foreground">
            Released under the Apache License 2.0. Bug reports, ideas and pull requests are welcome.
          </p>
          <Button variant="outline" size="sm" className="mt-4" asChild>
            <a href={links.github} target="_blank" rel="noreferrer">
              <GithubIcon className="size-4" />
              View on GitHub
            </a>
          </Button>
        </div>
        <div className="border-t border-rule px-5 py-8 sm:px-8 md:border-t-0 md:border-l">
          <p className="font-mono text-[11px] tracking-wide text-muted-foreground uppercase">Sponsor</p>
          <p className="mt-2 text-sm leading-relaxed text-muted-foreground">
            If TableGrip saves you time, you can support its development.
          </p>
          <div className="mt-4 flex flex-wrap gap-2">
            <Button variant="outline" size="sm" asChild>
              <a href={links.sponsors} target="_blank" rel="noreferrer">
                <Heart />
                GitHub Sponsors
              </a>
            </Button>
            <Button variant="ghost" size="sm" asChild>
              <a href={links.kofi} target="_blank" rel="noreferrer">
                Ko-fi
              </a>
            </Button>
            <Button variant="ghost" size="sm" asChild>
              <a href={links.paypal} target="_blank" rel="noreferrer">
                PayPal
              </a>
            </Button>
          </div>
        </div>
      </div>
    </Section>
  )
}
