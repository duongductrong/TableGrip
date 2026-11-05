import { Section, SectionHeader } from '#/components/site/frame'
import { Badge } from '#/components/ui/badge'
import { importSources } from '#/content/site'

export function MoveIn() {
  return (
    <Section>
      <SectionHeader
        title="Bring your connections"
        description="Import from the client you use today, or paste any connection URL. Passwords go to the system keychain, never to a file."
      />
      <div className="flex flex-wrap gap-2 border-t border-rule px-5 py-7 sm:px-8">
        {importSources.map((source) => (
          <Badge key={source} variant="outline" className="h-7 rounded-md border-rule bg-raised px-2.5 text-[13px] font-normal">
            {source}
          </Badge>
        ))}
      </div>
    </Section>
  )
}
