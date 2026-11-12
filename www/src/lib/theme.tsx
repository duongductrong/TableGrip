import * as React from 'react'

export type ThemeChoice = 'system' | 'light' | 'dark'
export type ResolvedTheme = 'light' | 'dark'

const STORAGE_KEY = 'tablegrip-theme'

/**
 * Runs in <head> before the page paints, so a server-rendered page never flashes the wrong theme. Kept as a string
 * because it must not wait for React.
 */
export const themeScript = `(function(){try{var c=localStorage.getItem('${STORAGE_KEY}')||'system';var d=c==='dark'||(c==='system'&&matchMedia('(prefers-color-scheme: dark)').matches);var r=document.documentElement;r.classList.toggle('dark',d);r.dataset.themeChoice=c;}catch(e){}})();`

type ThemeContextValue = {
  choice: ThemeChoice
  resolved: ResolvedTheme
  setChoice: (choice: ThemeChoice) => void
}

const ThemeContext = React.createContext<ThemeContextValue | null>(null)

function systemTheme(): ResolvedTheme {
  return typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

function readChoice(): ThemeChoice {
  try {
    const value = localStorage.getItem(STORAGE_KEY)
    return value === 'light' || value === 'dark' ? value : 'system'
  } catch {
    return 'system'
  }
}

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  // The server can't know the visitor's theme; the head script has already applied it, React catches up after mount.
  const [choice, setChoiceState] = React.useState<ThemeChoice>('system')
  const [system, setSystem] = React.useState<ResolvedTheme>('light')
  const [mounted, setMounted] = React.useState(false)

  React.useEffect(() => {
    setChoiceState(readChoice())
    setMounted(true)
    setSystem(systemTheme())
    const query = window.matchMedia('(prefers-color-scheme: dark)')
    const onChange = () => setSystem(query.matches ? 'dark' : 'light')
    query.addEventListener('change', onChange)
    return () => query.removeEventListener('change', onChange)
  }, [])

  const resolved: ResolvedTheme = choice === 'system' ? system : choice

  React.useEffect(() => {
    // Until the real choice is read, leave what the head script applied alone (the defaults here would force light).
    if (!mounted) return
    const root = document.documentElement
    root.classList.toggle('dark', resolved === 'dark')
    root.dataset.themeChoice = choice
  }, [choice, resolved, mounted])

  const setChoice = React.useCallback((next: ThemeChoice) => {
    setChoiceState(next)
    try {
      localStorage.setItem(STORAGE_KEY, next)
    } catch {
      /* private mode: the choice lasts for this page only */
    }
  }, [])

  const value = React.useMemo(() => ({ choice, resolved, setChoice }), [choice, resolved, setChoice])
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
}

export function useTheme() {
  const context = React.useContext(ThemeContext)
  if (!context) throw new Error('useTheme must be used inside <ThemeProvider>')
  return context
}
