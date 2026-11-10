import * as React from 'react'
import { Check, Monitor, Moon, Sun } from 'lucide-react'
import { DropdownMenu } from 'radix-ui'

import { useTheme, type ThemeChoice } from '#/lib/theme'
import { cn } from '#/lib/utils'

const options: { value: ThemeChoice; label: string; icon: typeof Sun }[] = [
  { value: 'light', label: 'Light', icon: Sun },
  { value: 'dark', label: 'Dark', icon: Moon },
  { value: 'system', label: 'System', icon: Monitor },
]

const prefersDark = () => window.matchMedia('(prefers-color-scheme: dark)').matches

/**
 * One icon button for the site theme. The sun and moon trade places as the theme flips, a small menu offers Light,
 * Dark and System, and where the browser supports view transitions the new theme spreads out in a circle from the
 * button instead of cutting over.
 */
export function ThemeToggle({ className, align = 'end' }: { className?: string; align?: 'start' | 'end' }) {
  const { choice, resolved, setChoice } = useTheme()
  const button = React.useRef<HTMLButtonElement>(null)

  const choose = (next: ThemeChoice) => {
    const dark = next === 'dark' || (next === 'system' && prefersDark())
    const apply = () => {
      document.documentElement.classList.toggle('dark', dark)
      setChoice(next)
    }
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    if (dark === (resolved === 'dark') || reduced || !document.startViewTransition) return apply()

    const rect = button.current?.getBoundingClientRect()
    const x = rect ? rect.left + rect.width / 2 : window.innerWidth - 40
    const y = rect ? rect.top + rect.height / 2 : 28
    const radius = Math.hypot(Math.max(x, window.innerWidth - x), Math.max(y, window.innerHeight - y))
    document.documentElement.dataset.themeSwitching = ''
    const transition = document.startViewTransition(apply)
    transition.ready.then(() => {
      document.documentElement.animate(
        { clipPath: [`circle(0px at ${x}px ${y}px)`, `circle(${radius}px at ${x}px ${y}px)`] },
        { duration: 450, easing: 'cubic-bezier(0.4, 0, 0.2, 1)', pseudoElement: '::view-transition-new(root)' },
      )
    })
    transition.finished.finally(() => delete document.documentElement.dataset.themeSwitching)
  }

  return (
    <DropdownMenu.Root modal={false}>
      <DropdownMenu.Trigger asChild>
        <button
          ref={button}
          type="button"
          aria-label="Theme"
          className={cn(
            'relative flex size-8 items-center justify-center rounded-md text-muted-foreground outline-none transition-colors hover:bg-surface-strong hover:text-foreground focus-visible:ring-[3px] focus-visible:ring-ring/50 data-[state=open]:bg-surface-strong data-[state=open]:text-foreground',
            className,
          )}
        >
          <Sun className="size-4 rotate-0 scale-100 transition-all duration-300 dark:-rotate-90 dark:scale-0" />
          <Moon className="absolute size-4 rotate-90 scale-0 transition-all duration-300 dark:rotate-0 dark:scale-100" />
        </button>
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content
          align={align}
          sideOffset={8}
          className="z-50 min-w-36 rounded-lg border border-rule bg-popover p-1 text-popover-foreground shadow-lg data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=closed]:zoom-out-95 data-[state=open]:animate-in data-[state=open]:fade-in-0 data-[state=open]:zoom-in-95"
        >
          {options.map(({ value, label, icon: Icon }) => (
            <DropdownMenu.Item
              key={value}
              onSelect={() => choose(value)}
              className="flex cursor-default items-center gap-2.5 rounded-md px-2 py-1.5 text-[13px] outline-none select-none data-[highlighted]:bg-surface-strong"
            >
              <Icon className="size-3.5 text-muted-foreground" />
              {label}
              <Check className={cn('ml-auto size-3.5', choice === value ? 'opacity-100' : 'opacity-0')} />
            </DropdownMenu.Item>
          ))}
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  )
}
