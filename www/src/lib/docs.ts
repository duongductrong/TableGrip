import { Marked, type Tokens } from 'marked'

import { docsNav } from '#/content/docs-nav'
import { shotSizes } from '#/components/site/product-shot'

/**
 * The documentation lives in src/content/docs/*.md: plain Markdown with a small front matter block (title,
 * description). The sidebar order and groups come from content/docs-nav.ts. This module turns a page into HTML
 * plus its table of contents, on the server and in the browser alike.
 *
 * Markdown extras:
 *   ![Alt text](shot:editor)     a product screenshot, light or dark to match the site (public/screenshots)
 *   > [!NOTE] / [!TIP] / [!WARNING]  a callout
 *   <kbd>⌘</kbd>                 inline HTML is allowed
 */

const files = import.meta.glob('../content/docs/*.md', { query: '?raw', import: 'default', eager: true }) as Record<
  string,
  string
>

/** `nav` is the sidebar label, when the title is too long for it. */
export type DocMeta = { slug: string; title: string; nav: string; description: string; group: string }
export type TocItem = { id: string; text: string; depth: 2 | 3 }
export type DocPage = DocMeta & { html: string; toc: TocItem[] }

function parseFrontMatter(raw: string): { data: Record<string, string>; body: string } {
  const match = /^---\n([\s\S]*?)\n---\n?/.exec(raw)
  if (!match) return { data: {}, body: raw }
  const data: Record<string, string> = {}
  for (const line of match[1].split('\n')) {
    const i = line.indexOf(':')
    if (i > 0) data[line.slice(0, i).trim()] = line.slice(i + 1).trim().replace(/^['"]|['"]$/g, '')
  }
  return { data, body: raw.slice(match[0].length) }
}

const sources = new Map<string, { data: Record<string, string>; body: string }>()
for (const [path, raw] of Object.entries(files)) {
  const slug = path.split('/').pop()!.replace(/\.md$/, '')
  sources.set(slug, parseFrontMatter(raw.replace(/\r\n/g, '\n')))
}

/** Every page in sidebar order. A slug in the nav without a file is a mistake caught here, at import time. */
export const docs: DocMeta[] = docsNav.flatMap((group) =>
  group.items.map((slug) => {
    const source = sources.get(slug)
    if (!source) throw new Error(`docs-nav lists "${slug}" but src/content/docs/${slug}.md does not exist`)
    const title = source.data.title ?? slug
    return { slug, group: group.title, title, nav: source.data.nav ?? title, description: source.data.description ?? '' }
  }),
)

const bySlug = new Map(docs.map((d) => [d.slug, d]))

export function slugify(text: string) {
  return text
    .toLowerCase()
    .replace(/<[^>]+>/g, '')
    .replace(/&[a-z]+;/g, '')
    .replace(/[^\p{L}\p{N}\s-]/gu, '')
    .trim()
    .replace(/\s+/g, '-')
}

const escape = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

const decode = (s: string) =>
  s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, '&')

const calloutLabels: Record<string, string> = { NOTE: 'Note', TIP: 'Tip', WARNING: 'Warning', IMPORTANT: 'Important' }

function shotHtml(scene: string, alt: string) {
  const size = shotSizes[scene]
  const dims = size ? ` width="${size.width}" height="${size.height}"` : ''
  const img = (theme: 'light' | 'dark', cls: string) =>
    `<img src="/screenshots/${scene}-${theme}.webp" alt="${escape(alt)}"${dims} loading="lazy" decoding="async" class="${cls}">`
  return (
    // A shot is never shown larger than its own size at 2x, so a small dialog doesn't fill the whole column.
    `<figure class="doc-shot"${size ? ` style="max-width:${size.width / 2}px"` : ''}>` +
    `<div class="doc-shot-frame">${img('light', 'block h-auto w-full dark:hidden')}${img('dark', 'hidden h-auto w-full dark:block')}</div>` +
    (alt ? `<figcaption>${escape(alt)}</figcaption>` : '') +
    `</figure>`
  )
}

/** Markdown to HTML with the docs' extras (anchored headings, callouts, code blocks, screenshots). */
export function renderMarkdown(body: string): { html: string; toc: TocItem[] } {
  const toc: TocItem[] = []
  const used = new Map<string, number>()
  const marked = new Marked({ gfm: true })
  marked.use({
    renderer: {
      heading(this: { parser: { parseInline: (t: Tokens.Generic[]) => string } }, token: Tokens.Heading) {
        const inner = this.parser.parseInline(token.tokens)
        if (token.depth !== 2 && token.depth !== 3) return `<h${token.depth}>${inner}</h${token.depth}>\n`
        let id = slugify(token.text) || 'section'
        const n = used.get(id) ?? 0
        used.set(id, n + 1)
        if (n) id = `${id}-${n}`
        toc.push({ id, text: decode(inner.replace(/<[^>]+>/g, '')), depth: token.depth })
        return `<h${token.depth} id="${id}"><a class="doc-anchor" href="#${id}" aria-label="Link to this section">#</a>${inner}</h${token.depth}>\n`
      },
      image({ href, text }: Tokens.Image) {
        if (href.startsWith('shot:')) return shotHtml(href.slice(5), text)
        return `<img src="${escape(href)}" alt="${escape(text)}" loading="lazy">`
      },
      table(this: { parser: { parseInline: (t: Tokens.Generic[]) => string } }, token: Tokens.Table) {
        const cell = (c: Tokens.TableCell, tag: 'th' | 'td') => `<${tag}>${this.parser.parseInline(c.tokens)}</${tag}>`
        const head = `<tr>${token.header.map((c) => cell(c, 'th')).join('')}</tr>`
        const rows = token.rows.map((r) => `<tr>${r.map((c) => cell(c, 'td')).join('')}</tr>`).join('')
        return `<div class="doc-table"><table><thead>${head}</thead><tbody>${rows}</tbody></table></div>\n`
      },
      blockquote(this: { parser: { parse: (t: Tokens.Generic[]) => string } }, token: Tokens.Blockquote) {
        const html = this.parser.parse(token.tokens)
        const match = /^<p>\[!(NOTE|TIP|WARNING|IMPORTANT)\]\s*/.exec(html)
        if (!match) return `<blockquote>${html}</blockquote>\n`
        const kind = match[1]
        const rest = html.slice(match[0].length).replace(/^<\/p>\s*/, '')
        return `<div class="doc-callout" data-kind="${kind.toLowerCase()}"><p class="doc-callout-title">${calloutLabels[kind]}</p><p>${rest}</div>\n`.replace(
          '<p><p>',
          '<p>',
        )
      },
      code({ text, lang }: Tokens.Code) {
        const label = lang ? `<span class="doc-code-lang">${escape(lang)}</span>` : ''
        return `<div class="doc-code">${label}<button type="button" class="doc-copy" data-copy aria-label="Copy code">Copy</button><pre><code>${escape(text)}</code></pre></div>\n`
      },
      link(this: { parser: { parseInline: (t: Tokens.Generic[]) => string } }, { href, title, tokens }: Tokens.Link) {
        const inner = this.parser.parseInline(tokens)
        const external = /^https?:\/\//.test(href)
        const t = title ? ` title="${escape(title)}"` : ''
        return external
          ? `<a href="${escape(href)}"${t} target="_blank" rel="noreferrer">${inner}</a>`
          : `<a href="${escape(href)}"${t}>${inner}</a>`
      },
    },
  })
  const html = marked.parse(body, { async: false }) as string
  return { html, toc }
}

const cache = new Map<string, DocPage>()

export function getDoc(slug: string): DocPage | undefined {
  const meta = bySlug.get(slug)
  const source = sources.get(slug)
  if (!meta || !source) return undefined
  let page = cache.get(slug)
  if (!page) {
    page = { ...meta, ...renderMarkdown(source.body) }
    cache.set(slug, page)
  }
  return page
}

export function neighbours(slug: string) {
  const i = docs.findIndex((d) => d.slug === slug)
  return { prev: i > 0 ? docs[i - 1] : undefined, next: i >= 0 && i < docs.length - 1 ? docs[i + 1] : undefined }
}
