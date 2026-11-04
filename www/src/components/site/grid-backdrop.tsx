import { experiments } from '#/lib/experiments'

/**
 * Experiment: a soft square grid under the whole page, strongest behind the hero and fading as you scroll. It sits
 * below every section and never catches the pointer. Switch it off in lib/experiments.ts.
 */
export function GridBackdrop() {
  if (!experiments.gridBackdrop) return null
  return <div aria-hidden className="grid-backdrop pointer-events-none absolute inset-0 -z-10" />
}
