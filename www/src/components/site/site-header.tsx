import { Bot, ChevronDown, Code2, Database, Download, Network, Table2, type LucideIcon } from 'lucide-react'
import { NavigationMenu } from 'radix-ui'

import { DownloadLink } from '#/components/site/download-dialog'
import { GithubIcon } from '#/components/site/github-icon'
import { SiteLink } from '#/components/site/site-link'
import { ThemeToggle } from '#/components/site/theme-toggle'
import { Button, buttonVariants } from '#/components/ui/button'
import { featureMenu, links, nav, type FeatureMenuIcon } from '#/content/site'
import { cn } from '#/lib/utils'

const featureIcons: Record<FeatureMenuIcon, LucideIcon> = {
  editor: Code2,
  grid: Table2,
  schema: Network,
  agent: Bot,
  databases: Database,
}

function FeaturesMenu() {
  return (
    <NavigationMenu.Item>
      <NavigationMenu.Trigger
        className={cn(buttonVariants({ variant: 'ghost', size: 'sm' }), 'group data-[state=open]:bg-surface-strong data-[state=open]:text-foreground')}
      >
        Features
        <ChevronDown className="size-3.5! transition-transform duration-200 group-data-[state=open]:rotate-180" aria-hidden />
      </NavigationMenu.Trigger>
      <NavigationMenu.Content className="absolute top-full left-0 pt-2 data-[motion^=from-]:animate-in data-[motion^=from-]:fade-in data-[motion^=to-]:animate-out data-[motion^=to-]:fade-out">
        <ul className="grid w-[26rem] gap-0.5 rounded-lg border border-rule bg-popover p-1.5 text-popover-foreground shadow-lg">
          {featureMenu.map((item) => {
            const Icon = featureIcons[item.icon]
            return (
              <li key={item.href}>
                <NavigationMenu.Link asChild>
                  <SiteLink
                    href={item.href}
                    className="flex items-start gap-3 rounded-md p-2.5 outline-none transition-colors hover:bg-surface-strong focus-visible:bg-surface-strong"
                  >
                    <span className="mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-md border border-rule bg-surface text-muted-foreground">
                      <Icon className="size-4" aria-hidden />
                    </span>
                    <span className="min-w-0">
                      <span className="block text-sm font-medium">{item.label}</span>
                      <span className="block text-[13px] leading-snug text-muted-foreground">{item.description}</span>
                    </span>
                  </SiteLink>
                </NavigationMenu.Link>
              </li>
            )
          })}
        </ul>
      </NavigationMenu.Content>
    </NavigationMenu.Item>
  )
}

export function SiteHeader() {
  return (
    <header className="sticky top-0 z-40 border-b border-rule bg-background/85 backdrop-blur supports-[backdrop-filter]:bg-background/75">
      <div className="mx-auto flex h-14 max-w-6xl items-center gap-6 border-x border-rule px-4 sm:px-6">
        <SiteLink href="/" className="flex items-center gap-2 font-semibold tracking-tight">
          <img src="/logo.png" alt="" width={24} height={24} className="size-6 rounded-[6px]" />
          TableGrip
        </SiteLink>
        <NavigationMenu.Root className="relative hidden md:block" aria-label="Main">
          <NavigationMenu.List className="flex items-center gap-1">
            <FeaturesMenu />
            {nav.map((item) => (
              <NavigationMenu.Item key={item.label}>
                <NavigationMenu.Link asChild>
                  <SiteLink href={item.href} className={buttonVariants({ variant: 'ghost', size: 'sm' })}>
                    {item.label}
                  </SiteLink>
                </NavigationMenu.Link>
              </NavigationMenu.Item>
            ))}
          </NavigationMenu.List>
        </NavigationMenu.Root>
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
            <DownloadLink>
              <Download />
              Download
            </DownloadLink>
          </Button>
        </div>
      </div>
    </header>
  )
}
