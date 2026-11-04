import { ArrowRight, Check } from 'lucide-react'

import { Section, SectionHeader } from '#/components/site/frame'
import { ProductShot, ShotThemeSwitch } from '#/components/site/product-shot'
import { Button } from '#/components/ui/button'
import { features, highlights } from '#/content/site'
import { SiteLink } from '#/components/site/site-link'

export function Features() {
  return (
    <Section id="features">
      <SectionHeader
        title="Working with your databases"
        description="The everyday loop of query, look and change, without leaving the keyboard."
        aside={<ShotThemeSwitch />}
      />
      {features.map((feature) => (
        <article
          key={feature.id}
          id={feature.id}
          className="grid border-t border-rule md:grid-cols-[minmax(0,1fr)_minmax(0,2fr)]"
        >
          <div className="flex flex-col justify-center px-5 py-8 sm:px-8 md:py-12">
            <h3 className="text-xl">{feature.title}</h3>
            <p className="mt-3 leading-relaxed text-muted-foreground">{feature.body}</p>
            <ul className="mt-5 space-y-2 text-sm">
              {feature.points.map((point) => (
                <li key={point} className="flex gap-2">
                  <Check className="mt-0.5 size-4 shrink-0 text-brand-text" />
                  {point}
                </li>
              ))}
            </ul>
            <Button variant="arrow" size="sm" className="mt-5 self-start" asChild>
              <SiteLink href={feature.docs}>
                Read the guide <ArrowRight />
              </SiteLink>
            </Button>
          </div>
          <div className="border-t border-rule bg-surface/60 p-3 sm:p-6 md:border-t-0 md:border-l">
            <ProductShot scene={feature.scene} alt={feature.alt} />
          </div>
        </article>
      ))}
      <div className="grid border-t border-rule sm:grid-cols-2 lg:grid-cols-4">
        {highlights.map((h, i) => (
          <div
            key={h.title}
            className={[
              'border-rule px-5 py-7 sm:px-8',
              i > 0 ? 'border-t sm:border-t-0' : '',
              i % 2 === 1 ? 'sm:border-l' : '',
              i >= 2 ? 'sm:border-t lg:border-t-0' : '',
              i > 0 ? 'lg:border-l' : '',
            ].join(' ')}
          >
            <h3 className="text-base">{h.title}</h3>
            <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{h.body}</p>
          </div>
        ))}
      </div>
    </Section>
  )
}
