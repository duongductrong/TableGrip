import * as React from 'react'
import { ArrowRight, Check, Copy, Folder, MousePointer2, Package, Search } from 'lucide-react'

import { SiteLink } from '#/components/site/site-link'
import { Button } from '#/components/ui/button'
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogTitle } from '#/components/ui/dialog'
import { links } from '#/content/site'
import {
  allTargets,
  defaultTarget,
  detectTarget,
  downloadSize,
  downloadUrl,
  fileName,
  latestVersion,
  targetInfo,
  type DownloadOs,
  type DownloadTarget,
} from '#/lib/download'
import { cn } from '#/lib/utils'

/**
 * Every download button on the site starts the installer straight from the latest GitHub release and, in the same
 * click, opens a dialog that walks through installing it. The button is a real link to the file, so it still works
 * before the page hydrates or with scripts off; the dialog is an extra on top.
 */

type DownloadContextValue = {
  /** The visitor's installer, once detected on the client. Undefined on the server and for phones and tablets. */
  detected: DownloadTarget | undefined
  /** Opens the guide for a target (or, without one, the system picker). */
  show: (target: DownloadTarget | undefined, started: boolean) => void
}

const DownloadContext = React.createContext<DownloadContextValue | null>(null)

function useDownload() {
  const value = React.useContext(DownloadContext)
  if (!value) throw new Error('DownloadLink must be inside DownloadProvider')
  return value
}

export function DownloadProvider({ children }: { children: React.ReactNode }) {
  const [detected, setDetected] = React.useState<DownloadTarget>()
  const [open, setOpen] = React.useState(false)
  const [target, setTarget] = React.useState<DownloadTarget>()
  const [started, setStarted] = React.useState(false)

  React.useEffect(() => {
    let alive = true
    detectTarget().then((t) => alive && setDetected(t))
    return () => {
      alive = false
    }
  }, [])

  const show = React.useCallback((next: DownloadTarget | undefined, didStart: boolean) => {
    setTarget(next)
    setStarted(didStart)
    setOpen(true)
  }, [])

  const value = React.useMemo(() => ({ detected, show }), [detected, show])

  return (
    <DownloadContext.Provider value={value}>
      {children}
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-w-[60rem]">
          <DownloadGuide
            target={target}
            started={started}
            onPick={(t) => {
              setTarget(t)
              setStarted(true)
            }}
          />
        </DialogContent>
      </Dialog>
    </DownloadContext.Provider>
  )
}

/** The visitor's detected installer, for labels like "Download for macOS". Undefined until detected. */
export function useDetectedTarget(): DownloadTarget | undefined {
  return useDownload().detected
}

type DownloadLinkProps =Omit<React.AnchorHTMLAttributes<HTMLAnchorElement>, 'href'> & {
  /** A fixed installer. Without one, the link uses the visitor's detected system. */
  target?: DownloadTarget
  /** An operating system: its installer for the visitor's architecture when they run it, else its default. */
  os?: DownloadOs
}

/** A link that downloads an installer and opens the install guide for it. */
export const DownloadLink = React.forwardRef<HTMLAnchorElement, DownloadLinkProps>(function DownloadLink(
  { target, os, onClick, children, ...props },
  ref,
) {
  const { detected, show } = useDownload()
  const resolved = target ?? (os ? (detected && targetInfo[detected].os === os ? detected : defaultTarget(os)) : detected)

  if (!latestVersion) {
    return (
      <a ref={ref} href={links.releases} target="_blank" rel="noreferrer" onClick={onClick} {...props}>
        {children}
      </a>
    )
  }

  return (
    <a
      ref={ref}
      // Without a known system (a phone, or before detection) the link opens the picker instead of a file.
      href={resolved ? downloadUrl(resolved) : '/#download'}
      onClick={(event) => {
        onClick?.(event)
        if (event.defaultPrevented) return
        if (!resolved) event.preventDefault()
        show(resolved, Boolean(resolved))
      }}
      {...props}
    >
      {children}
    </a>
  )
})

const osNoun: Record<DownloadOs, string> = { macos: 'your Mac', windows: 'Windows', linux: 'Linux' }

function DownloadGuide({
  target,
  started,
  onPick,
}: {
  target: DownloadTarget | undefined
  started: boolean
  onPick: (target: DownloadTarget) => void
}) {
  if (!target) return <SystemPicker onPick={onPick} />

  const info = targetInfo[target]
  const file = fileName(target)
  const size = downloadSize(target)
  const steps = guides[info.os](file)

  return (
    <>
      <div className="px-6 pt-7 pb-6 pr-14 sm:px-8 sm:pt-8">
        <p className="flex items-center gap-2 text-[13px] text-muted-foreground">
          <span className="relative flex size-2">
            {started && <span className="absolute inline-flex size-full animate-ping rounded-full bg-success opacity-60" />}
            <span className={cn('relative inline-flex size-2 rounded-full', started ? 'bg-success' : 'bg-rule-strong')} />
          </span>
          {started ? 'Download started' : 'Ready to download'}
        </p>
        <DialogTitle className="mt-3 text-[28px] leading-tight sm:text-[34px]">Install TableGrip on {osNoun[info.os]}</DialogTitle>
        <DialogDescription className="mt-3 max-w-2xl text-[15px] leading-relaxed">
          <span className="text-foreground">
            TableGrip {latestVersion} for {info.label}
          </span>
          {size ? <span className="font-mono text-[13px]"> · {size} MB</span> : null}. When the download finishes, follow
          these three steps. If it didn’t start,{' '}
          <a
            href={downloadUrl(target)}
            onClick={() => onPick(target)}
            className="text-foreground underline decoration-rule-strong underline-offset-4 hover:decoration-foreground"
          >
            download it again
          </a>
          .
        </DialogDescription>
      </div>

      <ol className="grid border-y border-rule md:grid-cols-3">
        {steps.map((step, i) => (
          <li key={step.title} className={cn('flex flex-col border-rule', i > 0 && 'border-t md:border-t-0 md:border-l')}>
            <div className="relative flex h-44 items-center justify-center overflow-hidden border-b border-rule bg-surface/70">
              <StepBackdrop />
              <div className="relative">{step.art}</div>
            </div>
            <div className="px-6 py-5 sm:px-7">
              <p className="font-mono text-[11px] tracking-wide text-muted-foreground uppercase">Step {i + 1}</p>
              <h3 className="mt-1.5 text-lg">{step.title}</h3>
              <div className="mt-1.5 text-sm leading-relaxed text-muted-foreground">{step.body}</div>
            </div>
          </li>
        ))}
      </ol>

      <div className="flex flex-col gap-4 px-6 py-5 sm:flex-row sm:items-end sm:justify-between sm:px-8">
        <div className="space-y-1.5 text-[13px] leading-relaxed text-muted-foreground">
          <p>{notes[info.os]}</p>
          <p>
            Other systems:{' '}
            {allTargets
              .filter((t) => t !== target)
              .map((t, i) => (
                <React.Fragment key={t}>
                  {i > 0 && ' · '}
                  <a
                    href={downloadUrl(t)}
                    onClick={() => onPick(t)}
                    className="text-foreground underline decoration-rule-strong underline-offset-4 hover:decoration-foreground"
                  >
                    {targetInfo[t].short}
                  </a>
                </React.Fragment>
              ))}
            {' · '}
            <DialogClose asChild>
              <SiteLink href="/docs/installation" className="text-brand-text hover:underline underline-offset-4">
                Installation guide
              </SiteLink>
            </DialogClose>
          </p>
        </div>
        <DialogClose asChild>
          <Button variant="brand" className="shrink-0 self-end sm:self-auto">
            Got it
          </Button>
        </DialogClose>
      </div>
    </>
  )
}

/** Shown when the system couldn't be told (a phone, a tablet, ChromeOS): pick an installer to download. */
function SystemPicker({ onPick }: { onPick: (target: DownloadTarget) => void }) {
  return (
    <>
      <div className="px-6 pt-7 pb-6 pr-14 sm:px-8 sm:pt-8">
        <DialogTitle className="text-[28px] leading-tight">Download TableGrip</DialogTitle>
        <DialogDescription className="mt-3 max-w-xl text-[15px] leading-relaxed">
          TableGrip {latestVersion} is a desktop app for macOS, Windows and Linux. Pick the installer for your computer.
        </DialogDescription>
      </div>
      <div className="grid border-t border-rule sm:grid-cols-2">
        {allTargets.map((t, i) => (
          <a
            key={t}
            href={downloadUrl(t)}
            onClick={() => onPick(t)}
            className={cn(
              'group flex items-center justify-between gap-4 border-rule px-6 py-5 transition-colors hover:bg-surface sm:px-8',
              i > 0 && 'border-t',
              i === 1 && 'sm:border-t-0 sm:border-l',
              i === 3 && 'sm:border-l',
            )}
          >
            <div>
              <div className="font-medium">{targetInfo[t].label}</div>
              <div className="mt-0.5 font-mono text-[12px] text-muted-foreground">{fileName(t)}</div>
            </div>
            <ArrowRight className="size-4 text-muted-foreground transition-transform group-hover:translate-x-0.5 group-hover:text-foreground" />
          </a>
        ))}
      </div>
    </>
  )
}

type Step = { title: string; body: React.ReactNode; art: React.ReactNode }

const guides: Record<DownloadOs, (file: string) => Step[]> = {
  macos: (file) => [
    {
      title: 'Open',
      body: (
        <>
          Open <FileName>{file}</FileName> from your Downloads folder.
        </>
      ),
      art: <DiskImageArt file={file} />,
    },
    {
      title: 'Install',
      body: 'Drag TableGrip into the Applications folder in the window that opens.',
      art: <DragToFolderArt />,
    },
    {
      title: 'Launch',
      body: 'Open TableGrip from Applications or Spotlight. It’s signed and notarized, so it opens without warnings.',
      art: <AppWindowArt />,
    },
  ],
  windows: (file) => [
    {
      title: 'Open',
      body: (
        <>
          Open <FileName>{file}</FileName> from your Downloads folder.
        </>
      ),
      art: <InstallerFileArt file={file} />,
    },
    {
      title: 'Install',
      body: 'Follow the installer. It installs for your user only, so no administrator rights are needed.',
      art: <SetupWindowArt />,
    },
    {
      title: 'Launch',
      body: 'Start TableGrip from the Start menu. It keeps itself up to date from then on.',
      art: <StartMenuArt />,
    },
  ],
  linux: (file) => [
    {
      title: 'Open a terminal',
      body: (
        <>
          Open a terminal in the folder <FileName>{file}</FileName> was saved to.
        </>
      ),
      art: <TerminalArt lines={['$ cd ~/Downloads', '$ ls', file]} />,
    },
    {
      title: 'Install',
      body: <CopyCommand command={`sudo apt install ./${file}`} />,
      art: <TerminalArt lines={[`$ sudo apt install ./${file}`, 'Setting up tablegrip …', 'Done.']} />,
    },
    {
      title: 'Launch',
      body: 'Start TableGrip from your applications menu.',
      art: <AppGridArt />,
    },
  ],
}

const notes: Record<DownloadOs, string> = {
  macos: 'Signed and notarized by Apple. TableGrip updates itself when a new version is out.',
  windows: 'If SmartScreen shows “Windows protected your PC”, choose More info → Run anyway.',
  linux: 'For Debian, Ubuntu and their derivatives. Passwords are kept in the Secret Service (GNOME Keyring or KWallet).',
}

function FileName({ children }: { children: React.ReactNode }) {
  return <span className="font-mono text-[12.5px] break-all text-foreground">{children}</span>
}

function CopyCommand({ command }: { command: string }) {
  const [copied, setCopied] = React.useState(false)
  return (
    <>
      <span>Install the package, then enter your password when asked:</span>
      <span className="mt-2 flex items-center gap-1 rounded-md border border-rule bg-surface py-1 pr-1 pl-2.5">
        <code className="min-w-0 flex-1 truncate font-mono text-[12px] text-foreground" title={command}>
          {command}
        </code>
        <button
          type="button"
          aria-label={copied ? 'Copied' : 'Copy command'}
          onClick={() => {
            navigator.clipboard?.writeText(command).then(() => {
              setCopied(true)
              setTimeout(() => setCopied(false), 1500)
            })
          }}
          className="flex size-7 shrink-0 items-center justify-center rounded text-muted-foreground transition-colors hover:bg-surface-strong hover:text-foreground"
        >
          {copied ? <Check className="size-3.5 text-success" /> : <Copy className="size-3.5" />}
        </button>
      </span>
    </>
  )
}

/* ——— Step illustrations, drawn with the site's own tokens so they follow light and dark. ——— */

function StepBackdrop() {
  return (
    <div
      aria-hidden
      className="absolute inset-0 opacity-60 [background-image:linear-gradient(var(--rule)_1px,transparent_1px),linear-gradient(90deg,var(--rule)_1px,transparent_1px)] [background-size:24px_24px] [mask-image:radial-gradient(ellipse_at_center,black_20%,transparent_75%)]"
    />
  )
}

function AppIcon({ className }: { className?: string }) {
  return <img src="/logo.png" alt="" className={cn('size-14 rounded-[14px] shadow-md', className)} />
}

function Chip({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <span className={cn('inline-block max-w-[16.5rem] truncate rounded bg-brand px-1.5 py-0.5 text-[11px] font-medium text-brand-foreground', className)}>
      {children}
    </span>
  )
}

function DiskImageArt({ file }: { file: string }) {
  return (
    <div className="flex flex-col items-center gap-3">
      <div className="relative h-[72px] w-[92px]">
        <div className="absolute inset-x-2 top-0 h-4 rounded-t-md border border-b-0 border-rule-strong bg-surface-strong" />
        <div className="absolute inset-x-0 top-3 bottom-0 flex flex-col items-center justify-center rounded-lg border border-rule-strong bg-raised shadow-sm">
          <img src="/logo.png" alt="" className="size-6 rounded-[6px]" />
          <div className="absolute bottom-2 left-3 h-[3px] w-9 rounded-full bg-rule-strong" />
          <div className="absolute right-3 bottom-1.5 size-2 rounded-full border border-brand bg-brand/30" />
        </div>
      </div>
      <Chip className="font-mono">{file}</Chip>
    </div>
  )
}

function DragToFolderArt() {
  return (
    <div className="flex items-center gap-5">
      <AppIcon />
      <ArrowRight className="size-6 text-muted-foreground" strokeWidth={1.5} />
      <div className="flex flex-col items-center gap-1.5">
        <Folder className="size-16 fill-brand/25 text-brand" strokeWidth={1.25} />
        <span className="text-[11px] text-muted-foreground">Applications</span>
      </div>
    </div>
  )
}

function TrafficLights() {
  return (
    <span className="flex gap-1">
      <span className="size-2 rounded-full bg-[#ff5f57]" />
      <span className="size-2 rounded-full bg-[#febc2e]" />
      <span className="size-2 rounded-full bg-[#28c840]" />
    </span>
  )
}

function AppWindowArt() {
  return (
    <div className="w-52 overflow-hidden rounded-lg border border-rule-strong bg-raised shadow-md">
      <div className="relative flex items-center border-b border-rule bg-surface-strong px-2.5 py-1.5">
        <TrafficLights />
        <span className="absolute inset-x-0 text-center text-[11px] font-medium">Applications</span>
      </div>
      <div className="relative flex flex-col items-center gap-2 py-4">
        <AppIcon className="size-11 rounded-[11px]" />
        <Chip>TableGrip</Chip>
        <MousePointer2 className="absolute top-10 left-[118px] size-5 fill-foreground text-background" strokeWidth={1.5} />
      </div>
    </div>
  )
}

function InstallerFileArt({ file }: { file: string }) {
  return (
    <div className="flex flex-col items-center gap-3">
      <div className="relative flex h-[76px] w-[62px] items-center justify-center rounded-md border border-rule-strong bg-raised shadow-sm">
        <div className="absolute top-0 right-0 size-4 rounded-bl-md border-b border-l border-rule-strong bg-surface-strong" />
        <Package className="size-7 text-brand" strokeWidth={1.5} />
      </div>
      <Chip className="font-mono">{file}</Chip>
    </div>
  )
}

function SetupWindowArt() {
  return (
    <div className="w-56 overflow-hidden rounded-md border border-rule-strong bg-raised shadow-md">
      <div className="flex items-center justify-between border-b border-rule bg-surface-strong py-1 pr-1 pl-2.5">
        <span className="text-[11px] font-medium">TableGrip Setup</span>
        <span className="flex gap-2.5 px-1.5 text-[10px] text-muted-foreground">
          <span>—</span>
          <span>☐</span>
          <span>✕</span>
        </span>
      </div>
      <div className="space-y-3 p-3">
        <div className="flex items-center gap-2.5">
          <img src="/logo.png" alt="" className="size-8 rounded-[7px]" />
          <div className="text-[11px] leading-snug">
            <div className="font-medium">Installing TableGrip</div>
            <div className="text-muted-foreground">Copying files…</div>
          </div>
        </div>
        <div className="h-1.5 overflow-hidden rounded-full bg-surface-strong">
          <div className="h-full w-[68%] rounded-full bg-brand" />
        </div>
        <div className="flex justify-end">
          <span className="rounded border border-rule bg-surface px-2.5 py-0.5 text-[10px]">Next</span>
        </div>
      </div>
    </div>
  )
}

function StartMenuArt() {
  return (
    <div className="w-56 space-y-2 rounded-lg border border-rule-strong bg-raised p-2.5 shadow-md">
      <div className="flex items-center gap-2 rounded-full border border-rule bg-surface px-2.5 py-1 text-[11px]">
        <Search className="size-3 text-muted-foreground" />
        TableGrip
        <span className="-ml-1.5 h-3 w-px animate-pulse bg-foreground" />
      </div>
      <div className="flex items-center gap-2.5 rounded-md bg-brand-subtle p-2">
        <img src="/logo.png" alt="" className="size-8 rounded-[7px]" />
        <div className="text-[11px] leading-snug">
          <div className="font-medium">TableGrip</div>
          <div className="text-muted-foreground">App</div>
        </div>
      </div>
    </div>
  )
}

function TerminalArt({ lines }: { lines: string[] }) {
  return (
    <div className="w-60 overflow-hidden rounded-lg border border-rule-strong bg-[oklch(20%_0_0)] shadow-md">
      <div className="flex items-center gap-1 border-b border-white/10 px-2.5 py-1.5">
        <span className="size-2 rounded-full bg-white/20" />
        <span className="size-2 rounded-full bg-white/20" />
        <span className="size-2 rounded-full bg-white/20" />
      </div>
      <div className="space-y-1 px-3 py-2.5 font-mono text-[10.5px] leading-snug text-white/85">
        {lines.map((line, i) => (
          <div key={i} className={cn('truncate', !line.startsWith('$') && 'text-white/50')}>
            {line.startsWith('$') ? (
              <>
                <span className="text-[oklch(78%_0.15_150)]">$</span>
                {line.slice(1)}
              </>
            ) : (
              line
            )}
          </div>
        ))}
      </div>
    </div>
  )
}

function AppGridArt() {
  return (
    <div className="grid grid-cols-3 gap-3 rounded-lg border border-rule-strong bg-raised p-3 shadow-md">
      {Array.from({ length: 6 }, (_, i) =>
        i === 1 ? (
          <div key={i} className="flex flex-col items-center gap-1">
            <AppIcon className="size-10 rounded-[10px] ring-2 ring-brand ring-offset-2 ring-offset-raised" />
            <span className="text-[10px] font-medium">TableGrip</span>
          </div>
        ) : (
          <div key={i} className="flex flex-col items-center gap-1">
            <div className="size-10 rounded-[10px] bg-surface-strong" />
            <span className="h-[7px] w-8 rounded-full bg-surface-strong" />
          </div>
        ),
      )}
    </div>
  )
}
