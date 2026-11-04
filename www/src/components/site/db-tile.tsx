import { cn } from '#/lib/utils'

/** The two-letter engine badge the app shows next to each connection. */
export function DbMonogram({ mono, color, className }: { mono: string; color: string; className?: string }) {
  return (
    <span
      aria-hidden
      className={cn('inline-flex size-7 items-center justify-center rounded-md font-mono text-[11px] font-semibold text-white', className)}
      style={{ backgroundColor: color }}
    >
      {mono}
    </span>
  )
}
