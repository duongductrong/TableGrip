import * as React from 'react'
import { Link, useRouter } from '@tanstack/react-router'
import { ArrowLeft, ArrowRight, SquarePen } from 'lucide-react'

import { DocsToc } from '#/components/docs/docs-toc'
import { links } from '#/content/site'
import { neighbours, type DocPage } from '#/lib/docs'

/**
 * One documentation page: the rendered Markdown, an "On this page" rail, and links to the pages before and after.
 * Links inside the Markdown to other docs pages navigate in place; code blocks get a working Copy button.
 */
export function DocsArticle({ page }: { page: DocPage }) {
  const router = useRouter()
  const { prev, next } = neighbours(page.slug)

  const onClick = (e: React.MouseEvent<HTMLDivElement>) => {
    const target = e.target as HTMLElement
    const copy = target.closest<HTMLButtonElement>('[data-copy]')
    if (copy) {
      const code = copy.parentElement?.querySelector('code')?.textContent ?? ''
      void navigator.clipboard?.writeText(code).then(() => {
        copy.textContent = 'Copied'
        setTimeout(() => (copy.textContent = 'Copy'), 1500)
      })
      return
    }
    const anchor = target.closest('a')
    if (!anchor || e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
    const href = anchor.getAttribute('href') ?? ''
    if (href.startsWith('/') && !href.startsWith('//')) {
      e.preventDefault()
      void router.navigate({ href })
    }
  }

  return (
    <div className="flex min-w-0 flex-1">
      <article className="min-w-0 flex-1 px-5 pt-10 pb-16 sm:px-10 lg:pt-12">
        <p className="mb-3 text-[13px] font-medium text-brand-text">{page.group}</p>
        <h1 className="text-[28px] leading-tight sm:text-3xl">{page.title}</h1>
        {page.description && <p className="mt-3 max-w-2xl text-base leading-relaxed text-muted-foreground sm:text-lg">{page.description}</p>}

        {/* biome-ignore lint: the content is our own Markdown, rendered at build time */}
        <div className="doc-content mt-8" onClick={onClick} dangerouslySetInnerHTML={{ __html: page.html }} />

        <div className="mt-14 flex flex-wrap items-center justify-between gap-3 border-t border-rule pt-6 text-[13px]">
          <a
            href={`${links.github}/edit/master/www/src/content/docs/${page.slug}.md`}
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-1.5 text-muted-foreground hover:text-foreground"
          >
            <SquarePen className="size-3.5" />
            Edit this page on GitHub
          </a>
        </div>

        <nav aria-label="Pages" className="mt-6 grid gap-3 sm:grid-cols-2">
          {prev ? (
            <Link
              to="/docs/$slug"
              params={{ slug: prev.slug }}
              className="group rounded-lg border border-rule px-4 py-3 transition-colors hover:border-rule-strong hover:bg-surface"
            >
              <span className="flex items-center gap-1 text-[13px] text-muted-foreground">
                <ArrowLeft className="size-3.5" /> Previous
              </span>
              <span className="mt-0.5 block text-sm font-medium">{prev.title}</span>
            </Link>
          ) : (
            <span />
          )}
          {next && (
            <Link
              to="/docs/$slug"
              params={{ slug: next.slug }}
              className="group rounded-lg border border-rule px-4 py-3 text-right transition-colors hover:border-rule-strong hover:bg-surface"
            >
              <span className="flex items-center justify-end gap-1 text-[13px] text-muted-foreground">
                Next <ArrowRight className="size-3.5" />
              </span>
              <span className="mt-0.5 block text-sm font-medium">{next.title}</span>
            </Link>
          )}
        </nav>
      </article>

      <aside className="hidden w-56 shrink-0 xl:block">
        <div className="sticky top-14 max-h-[calc(100dvh-3.5rem)] overflow-y-auto py-12 pr-6">
          <DocsToc key={page.slug} items={page.toc} />
        </div>
      </aside>
    </div>
  )
}
