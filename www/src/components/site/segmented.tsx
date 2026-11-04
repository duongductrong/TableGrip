import type * as React from 'react'

import { ToggleGroup, ToggleGroupItem } from '#/components/ui/toggle-group'
import { Tooltip, TooltipContent, TooltipTrigger } from '#/components/ui/tooltip'
import { cn } from '#/lib/utils'

export type SegmentedOption<T extends string> = {
  value: T
  label: string
  icon?: React.ReactNode
}

/**
 * A segmented control built on shadcn's ToggleGroup: a recessed track with the selected option raised. Used for the
 * site theme and for the screenshot theme. `iconOnly` hides labels and moves them into tooltips.
 */
export function Segmented<T extends string>({
  value,
  onValueChange,
  options,
  label,
  iconOnly = false,
  className,
}: {
  value: T
  onValueChange: (value: T) => void
  options: SegmentedOption<T>[]
  label: string
  iconOnly?: boolean
  className?: string
}) {
  return (
    <ToggleGroup
      type="single"
      aria-label={label}
      value={value}
      onValueChange={(next) => next && onValueChange(next as T)}
      className={cn('gap-0.5 rounded-lg border border-rule bg-surface p-0.5', className)}
    >
      {options.map((option) => {
        const item = (
          <ToggleGroupItem
            key={option.value}
            value={option.value}
            aria-label={option.label}
            className={cn(
              'h-7 min-w-7 rounded-md! border border-transparent px-2 text-[13px] text-muted-foreground shadow-none!',
              'hover:bg-transparent hover:text-foreground',
              'data-[state=on]:border-rule data-[state=on]:bg-raised data-[state=on]:text-foreground data-[state=on]:shadow-xs!',
              'dark:data-[state=on]:bg-surface-strong',
              iconOnly && 'px-0',
            )}
          >
            {option.icon}
            {!iconOnly && <span>{option.label}</span>}
          </ToggleGroupItem>
        )
        if (!iconOnly) return item
        return (
          <Tooltip key={option.value}>
            <TooltipTrigger asChild>{item}</TooltipTrigger>
            <TooltipContent side="bottom">{option.label}</TooltipContent>
          </Tooltip>
        )
      })}
    </ToggleGroup>
  )
}
