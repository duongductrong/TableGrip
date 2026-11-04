import * as React from 'react'
import { Monitor, Moon, Sun } from 'lucide-react'

import { Segmented } from '#/components/site/segmented'
import { cn } from '#/lib/utils'

export type Scene = 'grid' | 'filter' | 'editor' | 'structure' | 'agent'
export type ShotTheme = 'auto' | 'light' | 'dark'

/** Pixel size of each capture (www/scripts/capture.sh): the window alone, at 2x. */
const sizes: Record<Scene, { width: number; height: number }> = {
  grid: { width: 2560, height: 1600 },
  filter: { width: 2080, height: 1360 },
  editor: { width: 2080, height: 1400 },
  structure: { width: 2080, height: 1200 },
  agent: { width: 2720, height: 1800 },
}

/**
 * Screenshots follow the site theme ("auto") unless the visitor pins them to light or dark. Every shot on the page
 * shares one choice, so flipping it once compares the whole app in the other appearance.
 */
const ShotThemeContext = React.createContext<{ shotTheme: ShotTheme; setShotTheme: (t: ShotTheme) => void }>({
  shotTheme: 'auto',
  setShotTheme: () => {},
})

export function ShotThemeProvider({ children }: { children: React.ReactNode }) {
  const [shotTheme, setShotTheme] = React.useState<ShotTheme>('auto')
  const value = React.useMemo(() => ({ shotTheme, setShotTheme }), [shotTheme])
  return <ShotThemeContext.Provider value={value}>{children}</ShotThemeContext.Provider>
}

export function ShotThemeSwitch({ className }: { className?: string }) {
  const { shotTheme, setShotTheme } = React.useContext(ShotThemeContext)
  return (
    <Segmented<ShotTheme>
      className={className}
      label="Screenshot theme"
      value={shotTheme}
      onValueChange={setShotTheme}
      options={[
        { value: 'auto', label: 'Match site', icon: <Monitor /> },
        { value: 'light', label: 'Light', icon: <Sun /> },
        { value: 'dark', label: 'Dark', icon: <Moon /> },
      ]}
    />
  )
}

const src = (scene: Scene, theme: 'light' | 'dark') => `/screenshots/${scene}-${theme}.webp`

/** The app window in the appearance the visitor chose, with a hairline border and a soft shadow. */
export function ProductShot({
  scene,
  alt,
  priority = false,
  className,
}: {
  scene: Scene
  alt: string
  priority?: boolean
  className?: string
}) {
  const { shotTheme } = React.useContext(ShotThemeContext)
  const common = {
    alt,
    ...sizes[scene],
    loading: priority ? ('eager' as const) : ('lazy' as const),
    decoding: 'async' as const,
    className: 'block h-auto w-full',
  }
  return (
    <figure
      className={cn(
        'overflow-hidden rounded-[10px] border border-rule bg-surface shadow-[var(--shot-shadow)]',
        className,
      )}
    >
      {shotTheme === 'auto' ? (
        <>
          <img {...common} src={src(scene, 'light')} className={cn(common.className, 'dark:hidden')} />
          <img {...common} src={src(scene, 'dark')} className={cn(common.className, 'hidden dark:block')} />
        </>
      ) : (
        <img {...common} src={src(scene, shotTheme)} />
      )}
    </figure>
  )
}
