import { Monitor, Moon, Sun } from 'lucide-react'

import { Segmented } from '#/components/site/segmented'
import { useTheme, type ThemeChoice } from '#/lib/theme'

const options = [
  { value: 'system' as const, label: 'System', icon: <Monitor /> },
  { value: 'light' as const, label: 'Light', icon: <Sun /> },
  { value: 'dark' as const, label: 'Dark', icon: <Moon /> },
]

export function ThemeToggle({ withLabels = false }: { withLabels?: boolean }) {
  const { choice, setChoice } = useTheme()
  return (
    <Segmented<ThemeChoice>
      label="Site theme"
      value={choice}
      onValueChange={setChoice}
      options={options}
      iconOnly={!withLabels}
    />
  )
}
