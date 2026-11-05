import { createFileRoute, notFound } from '@tanstack/react-router'

import { DocsArticle } from '#/components/docs/docs-article'
import { getDoc } from '#/lib/docs'

export const Route = createFileRoute('/docs/$slug')({
  loader: ({ params }) => {
    const page = getDoc(params.slug)
    if (!page) throw notFound()
    return page
  },
  head: ({ loaderData }) => ({
    meta: loaderData
      ? [
          { title: `${loaderData.title} · TableGrip Docs` },
          { name: 'description', content: loaderData.description },
          { property: 'og:title', content: `${loaderData.title} · TableGrip Docs` },
          { property: 'og:description', content: loaderData.description },
        ]
      : [],
  }),
  component: DocPageView,
  notFoundComponent: () => (
    <div className="flex-1 px-10 py-20">
      <h1 className="text-3xl">Page not found</h1>
      <p className="mt-3 text-muted-foreground">
        This page doesn’t exist. Pick one from the list on the left, or start at the{' '}
        <a href="/docs/overview" className="underline underline-offset-4">overview</a>.
      </p>
    </div>
  ),
})

function DocPageView() {
  const page = Route.useLoaderData()
  return <DocsArticle page={page} />
}
