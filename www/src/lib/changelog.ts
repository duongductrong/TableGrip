import changelogSource from '../../../CHANGELOG.md?raw'

import { renderMarkdown } from '#/lib/docs'

/**
 * The changelog page is the repository's CHANGELOG.md, which the release workflow (scripts/release-notes.py) adds an
 * entry to for every release: `## [1.2.0] - 2026-10-01`, followed by the notes. Each entry becomes one release.
 */
export type Release = { version: string; date: string; id: string; html: string }

const heading = /^## \[([^\]]+)\](?:\s*-\s*(.+))?$/gm

function parse(raw: string): Release[] {
  const text = raw.replace(/\r\n/g, '\n')
  const matches = [...text.matchAll(heading)]
  return matches.map((match, i) => {
    const start = match.index + match[0].length
    const end = i + 1 < matches.length ? matches[i + 1].index : text.length
    const version = match[1].trim()
    return {
      version,
      date: match[2]?.trim() ?? '',
      id: `v${version.replace(/[^\w.-]/g, '-')}`,
      html: renderMarkdown(text.slice(start, end).trim()).html,
    }
  })
}

export const releases: Release[] = parse(changelogSource)

export function formatReleaseDate(date: string) {
  const d = new Date(`${date}T00:00:00Z`)
  if (Number.isNaN(d.getTime())) return date
  return d.toLocaleDateString('en-US', { year: 'numeric', month: 'long', day: 'numeric', timeZone: 'UTC' })
}
