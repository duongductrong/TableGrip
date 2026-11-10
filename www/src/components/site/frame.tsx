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

/** The small monospaced label above a heading ("01 · SQL editor"), the same voice as the install guide's "Step 1". */
export function Eyebrow({ index, children, className }: { index?: string; children: React.ReactNode; className?: string }) {
  return (
    <p className={cn('flex items-center gap-2 font-mono text-[11px] tracking-wide text-muted-foreground uppercase', className)}>
      {index && <span className="text-foreground">{index}</span>}
      {index && <span aria-hidden className="h-px w-4 bg-rule-strong" />}
      {children}
    </p>
  )
}

/** A hairline square grid that fades out from the middle: the backdrop every illustration and screenshot sits on. */
export function GridPattern({ className, size = 24 }: { className?: string; size?: number }) {
  return (
    <div
      aria-hidden
      style={{ backgroundSize: `${size}px ${size}px` }}
      className={cn(
        'pointer-events-none absolute inset-0 [background-image:linear-gradient(var(--rule)_1px,transparent_1px),linear-gradient(90deg,var(--rule)_1px,transparent_1px)] [mask-image:radial-gradient(ellipse_at_center,black_15%,transparent_72%)]',
        className,
      )}
    />
  )
}

/** A tinted, gridded panel that frames a screenshot or an illustration. */
export function Stage({ className, children }: { className?: string; children: React.ReactNode }) {
  return (
    <div className={cn('relative overflow-hidden bg-surface/70', className)}>
      <GridPattern size={32} />
      <div className="relative">{children}</div>
    </div>
  )
}

export function SectionHeader({
  eyebrow,
  index,
  title,
  description,
  aside,
  className,
}: {
  eyebrow?: React.ReactNode
  index?: string
  title: React.ReactNode
  description?: React.ReactNode
  aside?: React.ReactNode
  className?: string
}) {
  return (
    <div className={cn('flex flex-col gap-6 px-5 pt-16 pb-10 sm:px-8 sm:pt-20 md:flex-row md:items-end md:justify-between', className)}>
      <div className="max-w-2xl">
        {eyebrow && (
          <Eyebrow index={index} className="mb-4">
            {eyebrow}
          </Eyebrow>
        )}
        <h2 className="text-[30px] leading-[1.12] sm:text-[38px]">{title}</h2>
        {description && <p className="mt-4 text-[17px] leading-relaxed text-muted-foreground">{description}</p>}
      </div>
      {aside && <div className="shrink-0">{aside}</div>}
    </div>
  )
}
