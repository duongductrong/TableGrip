import { ArrowRight, FileText, KeyRound } from 'lucide-react'

import { Eyebrow, GridPattern, Section } from '#/components/site/frame'
import { importSources } from '#/content/site'

export function MoveIn() {
  return (
    <Section>
      <div className="grid md:grid-cols-2">
        <div className="px-5 pt-16 pb-12 sm:px-8 sm:pt-20">
          <Eyebrow index="04" className="mb-4">
            Move in
          </Eyebrow>
          <h2 className="text-[30px] leading-[1.12] sm:text-[38px]">Bring your connections with you.</h2>
          <p className="mt-4 text-[17px] leading-relaxed text-muted-foreground">
            Import from the client you use today, or paste any connection URL. Passwords go to the system keychain, never
            to a file.
          </p>
          <ul className="mt-8 grid grid-cols-2 gap-px overflow-hidden rounded-lg border border-rule bg-rule">
            {importSources.map((source) => (
              <li key={source} className="bg-background px-3.5 py-2.5 font-mono text-[12.5px]">
                {source}
              </li>
            ))}
          </ul>
        </div>

        <div className="relative flex min-h-80 items-center justify-center overflow-hidden border-t border-rule bg-surface/70 px-5 py-12 md:border-t-0 md:border-l">
          <GridPattern size={24} />
          <ImportArt />
        </div>
      </div>
    </Section>
  )
}

/** Sources → TableGrip → keychain, drawn with the site's tokens like the install guide's step art. */
function ImportArt() {
  return (
    <div aria-hidden className="relative flex items-center gap-4 sm:gap-6">
      <div className="flex flex-col gap-2">
        {['dbeaver.xml', '.pgpass', '.env'].map((file, i) => (
          <div
            key={file}
            className="flex items-center gap-2 rounded-md border border-rule-strong bg-raised px-2.5 py-1.5 shadow-sm"
            style={{ marginLeft: i * 10 }}
          >
            <FileText className="size-3.5 text-muted-foreground" strokeWidth={1.5} />
            <span className="font-mono text-[11px]">{file}</span>
          </div>
        ))}
      </div>
      <ArrowRight className="size-5 shrink-0 text-muted-foreground" strokeWidth={1.5} />
      <div className="flex flex-col items-center gap-2">
        <img src="/logo.png" alt="" className="size-14 rounded-[14px] shadow-md" />
        <span className="rounded bg-foreground px-1.5 py-0.5 text-[11px] font-medium text-background">TableGrip</span>
      </div>
      <ArrowRight className="size-5 shrink-0 text-muted-foreground" strokeWidth={1.5} />
      <div className="flex flex-col items-center gap-2">
        <span className="flex size-14 items-center justify-center rounded-[14px] border border-rule-strong bg-raised shadow-sm">
          <KeyRound className="size-6" strokeWidth={1.5} />
        </span>
        <span className="text-[11px] text-muted-foreground">Keychain</span>
      </div>
    </div>
  )
}
