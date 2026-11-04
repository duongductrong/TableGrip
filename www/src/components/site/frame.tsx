import type * as React from 'react'

import { cn } from '#/lib/utils'

/**
 * The page is drawn as one ruled sheet: a centred column with hairlines left and right, cut by full-width rules
 * between sections. A small cross marks each place a rule meets the column.
 */
export function Frame({ className, children }: { className?: string; children: React.ReactNode }) {
  return <div className={cn('mx-auto w-full max-w-6xl border-x border-rule', className)}>{children}</div>
}

function Cross({ className }: { className?: string }) {
  return (
    <svg
      aria-hidden
      viewBox="0 0 11 11"
      className={cn('pointer-events-none absolute z-10 hidden size-[11px] text-rule-strong md:block', className)}
    >
      <path d="M5.5 0v11M0 5.5h11" stroke="currentColor" strokeWidth="1" />
    </svg>
  )
}

export function Section({
  id,
  className,
  frameClassName,
  children,
  rule = true,
}: {
  id?: string
  className?: string
  frameClassName?: string
  children: React.ReactNode
  rule?: boolean
}) {
  return (
    <section id={id} className={cn('relative', rule && 'border-t border-rule', className)}>
      {rule && (
        <div className="pointer-events-none absolute inset-x-0 top-0 mx-auto max-w-6xl">
          <Cross className="-top-[5.5px] -left-[5.5px]" />
          <Cross className="-top-[5.5px] -right-[5.5px]" />
        </div>
      )}
      <Frame className={frameClassName}>{children}</Frame>
    </section>
  )
}

export function SectionHeader({
  title,
  description,
  aside,
  className,
}: {
  title: React.ReactNode
  description?: React.ReactNode
  aside?: React.ReactNode
  className?: string
}) {
  return (
    <div className={cn('flex flex-col gap-4 px-5 pt-14 pb-8 sm:px-8 md:flex-row md:items-end md:justify-between', className)}>
      <div className="max-w-2xl">
        <h2 className="text-[28px] leading-tight sm:text-3xl">{title}</h2>
        {description && <p className="mt-3 text-lg leading-relaxed text-muted-foreground">{description}</p>}
      </div>
      {aside && <div className="shrink-0">{aside}</div>}
    </div>
  )
}
