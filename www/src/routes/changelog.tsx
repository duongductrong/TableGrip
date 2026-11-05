import { createFileRoute } from '@tanstack/react-router'
import { ArrowUpRight } from 'lucide-react'

import { Frame } from '#/components/site/frame'
import { links } from '#/content/site'
import { formatReleaseDate, releases } from '#/lib/changelog'

const title = 'Changelog · TableGrip'
const description = 'What changed in each TableGrip release, newest first.'

export const Route = createFileRoute('/changelog')({
  head: () => ({
    meta: [
      { title },
      { name: 'description', content: description },
      { property: 'og:title', content: title },
      { property: 'og:description', content: description },
    ],
  }),
  component: ChangelogPage,
})

function ChangelogPage() {
  return (
    <Frame className="bg-background">
      <header className="border-b border-rule px-5 pt-12 pb-10 sm:px-10 lg:pt-16">
        <h1 className="text-[28px] leading-tight sm:text-4xl">Changelog</h1>
        <p className="mt-3 max-w-2xl text-base leading-relaxed text-muted-foreground sm:text-lg">
          {description} Installers for every version are on{' '}
          <a href={links.releases} target="_blank" rel="noreferrer" className="text-foreground underline underline-offset-4">
            GitHub Releases
          </a>
          .
        </p>
      </header>

      {releases.length === 0 ? (
        <div className="px-5 py-16 sm:px-10">
          <p className="text-muted-foreground">No releases have been listed here yet.</p>
          <a
            href={links.releases}
            target="_blank"
            rel="noreferrer"
            className="mt-4 inline-flex items-center gap-1 text-sm font-medium text-brand-text hover:underline underline-offset-4"
          >
            See GitHub Releases
            <ArrowUpRight className="size-4" />
          </a>
        </div>
      ) : (
        <ol>
          {releases.map((release) => (
            <li
              key={release.id}
              id={release.id}
              className="grid scroll-mt-20 gap-4 border-b border-rule px-5 py-10 last:border-b-0 sm:px-10 md:grid-cols-[12rem_1fr] md:gap-10"
            >
              <div className="md:sticky md:top-20 md:self-start">
                <a href={`#${release.id}`} className="text-lg font-semibold tracking-tight hover:underline underline-offset-4">
                  v{release.version}
                </a>
                {release.date && (
                  <p className="mt-1 text-[13px] text-muted-foreground">
                    <time dateTime={release.date}>{formatReleaseDate(release.date)}</time>
                  </p>
                )}
              </div>
              {/* biome-ignore lint: the content is the repository's own CHANGELOG.md */}
              <div className="doc-content min-w-0" dangerouslySetInnerHTML={{ __html: release.html }} />
            </li>
          ))}
        </ol>
      )}
    </Frame>
  )
}
