# TableGrip website

The landing page for TableGrip: [TanStack Start](https://tanstack.com/start) with server-side rendering, Tailwind CSS 4
and [shadcn/ui](https://ui.shadcn.com) components restyled for the site.

```bash
pnpm install
pnpm dev        # http://localhost:3000
pnpm build      # dist/ (server + client)
```

## Layout

```
src/
  styles.css                # design tokens (light and dark), the one place colours are defined
  lib/theme.tsx             # site theme: System / Light / Dark, saved in localStorage, applied before paint
  components/ui/            # shadcn/ui primitives (Button is retuned: brand, outline, ghost, arrow)
  components/site/          # building blocks: Frame/Section (the ruled grid), Segmented, ThemeToggle, ProductShot
  components/sections/      # the page's sections, composed in routes/index.tsx
  content/site.ts           # every piece of copy and every link
public/screenshots/         # <scene>-<light|dark>.webp, taken from the real app
```

Add more shadcn components with `pnpm dlx shadcn@latest add <name>`; they land in `src/components/ui/`.

## Screenshots

Every product image is a real screenshot of the app, in both appearances. The site shows the one matching its theme,
and visitors can pin all shots to Light or Dark with the switch above them.

To take them again (macOS):

```bash
www/scripts/capture.sh                 # every scene, dark and light
www/scripts/capture.sh dark            # dark only
www/scripts/capture.sh light editor    # one scene in one theme
```

The script builds a throwaway profile in `www/.demo-profile` with a seeded SQLite shop database
(`scripts/demo-profile.sh`), so your own connections never appear. For each scene it writes the session the app
restores on start (which tab is open), forces the theme in `settings.json`, launches the app with `./gradlew :app:run`,
captures the window (without the macOS shadow; the page draws its own) through `screencapture`, and converts it with
`cwebp`. The `editor` and
`agent` scenes click into the console and press ⌘↩ with `cliclick` to run the query, so the terminal needs
Accessibility permission (`brew install webp cliclick`).

Scenes are `grid` (hero), `filter`, `editor`, `structure` and `agent`. The agent scene reopens a finished chat seeded
by `demo-profile.sh` (`chats/demo-chat.json`); its numbers match the demo database. The app runs at 110% zoom and
feature scenes use a smaller window, so text stays readable on the page. To add a scene, extend `window_size()` and
`session()` in the script, then the `Scene` type and `sizes` map in `src/components/site/product-shot.tsx` (the
size is the image's pixel size, `webpinfo` prints it).
