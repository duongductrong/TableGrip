import { Download } from 'lucide-react'

import { GithubIcon } from '#/components/site/github-icon'
import { ThemeToggle } from '#/components/site/theme-toggle'
import { Button } from '#/components/ui/button'
import { links, nav } from '#/content/site'

export function SiteHeader() {
  return (
    <header className="sticky top-0 z-40 border-b border-rule bg-background/85 backdrop-blur supports-[backdrop-filter]:bg-background/75">
      <div className="mx-auto flex h-14 max-w-6xl items-center gap-6 border-x border-rule px-4 sm:px-6">
        <a href="/" className="flex items-center gap-2 font-semibold tracking-tight">
          <img src="/logo.png" alt="" width={24} height={24} className="size-6 rounded-[6px]" />
          TableGrip
        </a>
        <nav className="hidden items-center gap-1 md:flex" aria-label="Main">
          {nav.map((item) => (
            <Button key={item.label} variant="ghost" size="sm" asChild>
              <a href={item.href} {...(item.external ? { target: '_blank', rel: 'noreferrer' } : {})}>
                {item.label}
              </a>
            </Button>
          ))}
        </nav>
        <div className="ml-auto flex items-center gap-2">
          <div className="hidden sm:block">
            <ThemeToggle />
          </div>
          <Button variant="ghost" size="icon-sm" asChild>
            <a href={links.github} target="_blank" rel="noreferrer" aria-label="TableGrip on GitHub">
              <GithubIcon className="size-4" />
            </a>
          </Button>
          <Button variant="default" size="sm" asChild>
            <a href="#download">
              <Download />
              Download
            </a>
          </Button>
        </div>
      </div>
    </header>
  )
}
