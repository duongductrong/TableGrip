import { defineConfig } from 'vite'


import { tanstackStart } from '@tanstack/react-start/plugin/vite'

import viteReact from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { cloudflare } from '@cloudflare/vite-plugin'

const config = defineConfig({
  resolve: { tsconfigPaths: true },
  // The changelog page reads the repository's CHANGELOG.md, one level above the site.
  server: { fs: { allow: ['.', '../CHANGELOG.md'] } },
  plugins: [cloudflare({ viteEnvironment: { name: 'ssr' } }), tailwindcss(), tanstackStart(), viteReact()],
})

export default config
