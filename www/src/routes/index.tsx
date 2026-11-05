import { createFileRoute } from '@tanstack/react-router'

import { Agent } from '#/components/sections/agent'
import { Databases } from '#/components/sections/databases'
import { DownloadSection } from '#/components/sections/download'
import { Features } from '#/components/sections/features'
import { Hero } from '#/components/sections/hero'
import { MoveIn } from '#/components/sections/move-in'
import { ShotThemeProvider } from '#/components/site/product-shot'

export const Route = createFileRoute('/')({ component: Home })

function Home() {
  return (
    <ShotThemeProvider>
      <Hero />
      <Features />
      <Agent />
      <Databases />
      <MoveIn />
      <DownloadSection />
    </ShotThemeProvider>
  )
}
