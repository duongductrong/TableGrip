import * as React from 'react'
import { Link, type LinkProps } from '@tanstack/react-router'

type Props = Omit<React.AnchorHTMLAttributes<HTMLAnchorElement>, 'href'> & { href: string }

/**
 * A link that takes a plain href, as the content in content/site.ts does. A path on this site ("/changelog",
 * "/#features", "/docs/troubleshooting#faq") goes through the router, so moving between pages never reloads;
 * anything else is an ordinary anchor that opens in a new tab.
 */
export const SiteLink = React.forwardRef<HTMLAnchorElement, Props>(function SiteLink({ href, children, ...props }, ref) {
  if (!href.startsWith('/')) {
    return (
      <a ref={ref} href={href} target="_blank" rel="noreferrer" {...props}>
        {children}
      </a>
    )
  }
  const [path, hash] = href.split('#')
  return (
    <Link ref={ref} to={(path || '/') as LinkProps['to']} hash={hash} {...props}>
      {children}
    </Link>
  )
})
