import { ThemeToggle } from '#/components/site/theme-toggle'
import { links } from '#/content/site'

const columns = [
  {
    title: 'Product',
    items: [
      { label: 'Features', href: '#features' },
      { label: 'Databases', href: '#databases' },
      { label: 'Download', href: links.releases },
    ],
  },
  {
    title: 'Docs',
    items: [
      { label: 'User guide', href: links.guide },
      { label: 'Build from source', href: links.development },
      { label: 'Contributing', href: links.contributing },
    ],
  },
  {
    title: 'Support',
    items: [
      { label: 'GitHub Sponsors', href: links.sponsors },
      { label: 'Ko-fi', href: links.kofi },
      { label: 'PayPal', href: links.paypal },
    ],
  },
]

export function SiteFooter() {
  return (
    <footer className="border-t border-rule">
      <div className="mx-auto grid max-w-6xl gap-10 border-x border-rule px-5 py-12 sm:px-8 md:grid-cols-[1.4fr_repeat(3,1fr)]">
        <div className="space-y-4">
          <a href="/" className="flex items-center gap-2 font-semibold tracking-tight">
            <img src="/logo.png" alt="" width={24} height={24} className="size-6 rounded-[6px]" />
            TableGrip
          </a>
          <p className="max-w-xs text-sm leading-relaxed text-muted-foreground">
            A fast, native-feeling desktop database client. Free and open source under the{' '}
            <a href={links.license} className="underline underline-offset-4 hover:text-foreground">
              Apache License 2.0
            </a>
            .
          </p>
          <ThemeToggle withLabels />
        </div>
        {columns.map((column) => (
          <div key={column.title}>
            <h3 className="text-sm font-medium tracking-normal">{column.title}</h3>
            <ul className="mt-3 space-y-2 text-sm text-muted-foreground">
              {column.items.map((item) => (
                <li key={item.label}>
                  <a
                    href={item.href}
                    className="hover:text-foreground"
                    {...(item.href.startsWith('http') ? { target: '_blank', rel: 'noreferrer' } : {})}
                  >
                    {item.label}
                  </a>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>
      <div className="border-t border-rule">
        <div className="mx-auto max-w-6xl border-x border-rule px-5 py-5 text-xs text-muted-foreground sm:px-8">
          Built with Kotlin, Compose for Desktop and Jewel.
        </div>
      </div>
    </footer>
  )
}
