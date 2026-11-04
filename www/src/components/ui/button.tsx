import * as React from 'react'
import { cva, type VariantProps } from 'class-variance-authority'
import { Slot } from 'radix-ui'

import { cn } from '#/lib/utils'

/**
 * shadcn/ui's Button, retuned for the site: `brand` is the one filled call to action, `outline` and `ghost` stay
 * neutral, and `arrow` is the small accent text link ("Read the guide →") used under every section.
 */
const buttonVariants = cva(
  "inline-flex shrink-0 items-center justify-center gap-2 rounded-md text-sm font-medium whitespace-nowrap transition-colors outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-50 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
  {
    variants: {
      variant: {
        brand: 'bg-brand text-brand-foreground hover:bg-brand-hover shadow-xs',
        default: 'bg-foreground text-background hover:bg-foreground/85 shadow-xs',
        outline: 'border border-rule bg-raised hover:bg-surface-strong',
        ghost: 'text-muted-foreground hover:bg-surface-strong hover:text-foreground',
        arrow: 'h-auto! px-0! text-brand-text hover:underline underline-offset-4 [&_svg]:transition-transform hover:[&_svg]:translate-x-0.5',
        link: 'text-foreground underline-offset-4 hover:underline',
      },
      size: {
        default: 'h-9 px-4 has-[>svg]:px-3',
        sm: 'h-8 gap-1.5 px-3 has-[>svg]:px-2.5 text-[13px]',
        lg: 'h-11 px-5 text-[15px] has-[>svg]:px-4',
        icon: 'size-9',
        'icon-sm': 'size-8',
      },
    },
    defaultVariants: { variant: 'default', size: 'default' },
  },
)

function Button({
  className,
  variant,
  size,
  asChild = false,
  ...props
}: React.ComponentProps<'button'> & VariantProps<typeof buttonVariants> & { asChild?: boolean }) {
  const Comp = asChild ? Slot.Root : 'button'
  return (
    <Comp
      data-slot="button"
      data-variant={variant}
      className={cn(buttonVariants({ variant, size, className }))}
      {...props}
    />
  )
}

export { Button, buttonVariants }
