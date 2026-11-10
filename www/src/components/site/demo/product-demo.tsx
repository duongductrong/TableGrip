import * as React from 'react'
import {
  ArrowUp,
  Bookmark,
  Check,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  ChevronsLeft,
  Clock,
  Copy,
  Database,
  Download,
  Eye,
  FastForward,
  Filter,
  GitCompare,
  History,
  Keyboard,
  Loader2,
  Minus,
  PanelRight,
  Pause,
  Pin,
  Play,
  Plus,
  Redo2,
  RefreshCw,
  Save,
  Search,
  Settings,
  Sparkles,
  Square,
  SquareTerminal,
  Table2,
  TriangleAlert,
  Undo2,
  Zap,
} from 'lucide-react'

import { cn } from '#/lib/utils'

import * as T from './timeline'

const { S, prog, ease, easeOut, fade, typed } = T

// ===============================================================================================================
// Player

/**
 * The hero's product demo: TableGrip's window recreated in HTML and played from a script (./timeline.ts). It
 * autoplays while on screen, loops, and can be paused or jumped to a chapter. Visitors who prefer reduced motion
 * get a still frame and the chapter buttons.
 */
export function ProductDemo({ className }: { className?: string }) {
  const [t, setT] = React.useState(0)
  const [playing, setPlaying] = React.useState(true)
  const [onScreen, setOnScreen] = React.useState(false)
  const [scale, setScale] = React.useState(0)
  const outerRef = React.useRef<HTMLDivElement>(null)
  const stageRef = React.useRef<HTMLDivElement>(null)
  const cursorRef = React.useRef<HTMLDivElement>(null)
  const points = React.useRef<Record<string, { x: number; y: number }>>({})

  React.useEffect(() => {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      setPlaying(false)
      setT(T.SQL.chart + 1200)
    }
  }, [])

  React.useEffect(() => {
    const el = outerRef.current
    if (!el) return
    const ro = new ResizeObserver(([entry]) => setScale(entry.contentRect.width / T.W))
    ro.observe(el)
    const io = new IntersectionObserver(([entry]) => setOnScreen(entry.isIntersecting), { threshold: 0.15 })
    io.observe(el)
    return () => {
      ro.disconnect()
      io.disconnect()
    }
  }, [])

  React.useEffect(() => {
    if (!playing || !onScreen) return
    let raf = 0
    let last = performance.now()
    const tick = (now: number) => {
      const dt = Math.min(100, now - last)
      last = now
      setT((prev) => (prev + dt) % T.LOOP)
      raf = requestAnimationFrame(tick)
    }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [playing, onScreen])

  // The pointer glides between elements tagged data-target, read from the laid-out window each frame.
  React.useLayoutEffect(() => {
    const stage = stageRef.current
    const cursor = cursorRef.current
    if (!stage || !cursor || !scale) return
    const sr = stage.getBoundingClientRect()
    const point = (name: string) => {
      if (name === 'start') return { x: 820, y: 560 }
      const el = stage.querySelector(`[data-target="${name}"]`)
      if (el) {
        const r = el.getBoundingClientRect()
        const p = {
          x: (r.left - sr.left) / scale + Math.min(r.width / scale / 2, 60),
          y: (r.top - sr.top + r.height * 0.55) / scale,
        }
        points.current[name] = p
        return p
      }
      return points.current[name] ?? { x: 820, y: 560 }
    }
    const { from, to, p } = T.cursorAt(t)
    const a = point(from)
    const b = point(to)
    cursor.style.transform = `translate(${a.x + (b.x - a.x) * p}px, ${a.y + (b.y - a.y) * p}px)`
  })

  const chapterIndex = T.chapters.reduce((found, c, i) => (t >= c.start ? i : found), 0)
  const chapter = T.chapters[chapterIndex]
  const chapterEnd = T.chapters[chapterIndex + 1]?.start ?? T.LOOP

  return (
    <div className={className}>
      <div
        ref={outerRef}
        role="img"
        aria-label="An animated tour of TableGrip: connecting to a database, filtering and editing rows, writing SQL with schema-aware completion, asking the AI agent, and jumping anywhere with Command-P"
        className="tg-demo relative w-full overflow-hidden"
        style={{ aspectRatio: `${T.W} / ${T.H}` }}
      >
        <div
          ref={stageRef}
          aria-hidden
          className="absolute top-0 left-0 origin-top-left select-none"
          style={{ width: T.W, height: T.H, transform: `scale(${scale})`, visibility: scale ? 'visible' : 'hidden' }}
        >
          <AppWindow t={t} />
          <Keycaps t={t} />
          <Cursor ref={cursorRef} t={t} />
        </div>
      </div>

      {/* Chapters, drawn like the Features tabs: ruled cells, the playing one raised with a progress rule on top. */}
      <div className="flex border-t border-rule">
        <button
          type="button"
          onClick={() => setPlaying((p) => !p)}
          aria-label={playing ? 'Pause the demo' : 'Play the demo'}
          className="flex w-12 shrink-0 items-center justify-center text-muted-foreground transition-colors outline-none hover:bg-surface hover:text-foreground focus-visible:bg-surface sm:w-16"
        >
          {playing ? <Pause className="size-3.5" fill="currentColor" /> : <Play className="size-3.5" fill="currentColor" />}
        </button>
        <div className="grid flex-1 grid-cols-6">
          {T.chapters.map((c, i) => {
            const end = T.chapters[i + 1]?.start ?? T.LOOP
            const fill = i < chapterIndex ? 1 : i === chapterIndex ? prog(t, c.start, end) : 0
            const current = i === chapterIndex
            return (
              <button
                key={c.id}
                type="button"
                onClick={() => {
                  setT(c.start)
                  setPlaying(true)
                }}
                aria-label={`Jump to ${c.title}: ${c.caption}`}
                aria-current={current ? 'step' : undefined}
                className={cn(
                  'group relative min-w-0 border-l border-rule px-2 py-3 text-left transition-colors outline-none focus-visible:bg-surface sm:px-4 sm:py-4',
                  current ? 'bg-raised' : 'hover:bg-surface',
                )}
              >
                <span aria-hidden className="absolute inset-x-0 top-0 h-0.5 bg-rule">
                  <span className="block h-full bg-foreground" style={{ width: `${fill * 100}%` }} />
                </span>
                <span className="block font-mono text-[11px] tracking-wide text-muted-foreground">
                  {String(i + 1).padStart(2, '0')}
                </span>
                <span
                  className={cn(
                    'mt-1 hidden truncate text-[13px] font-semibold tracking-tight transition-colors sm:block',
                    current ? 'text-foreground' : 'text-muted-foreground group-hover:text-foreground',
                  )}
                >
                  {c.title}
                </span>
                <span className="mt-0.5 hidden text-[12px] leading-snug text-pretty text-muted-foreground lg:line-clamp-2">{c.caption}</span>
              </button>
            )
          })}
        </div>
      </div>
      <p className="border-t border-rule px-5 py-3 text-[13px] text-muted-foreground sm:hidden">
        <span className="font-medium text-foreground">{chapter.title}.</span> {chapter.caption}
        <span className="sr-only">{` (${Math.round(prog(t, chapter.start, chapterEnd) * 100)}%)`}</span>
      </p>
    </div>
  )
}

// ===============================================================================================================
// Overlays

const Cursor = React.forwardRef<HTMLDivElement, { t: number }>(function Cursor({ t }, ref) {
  const click = T.clicks.find((c) => t >= c.at && t < c.at + (c.double ? 600 : 450))
  const pressed = T.clicks.some((c) => (t >= c.at && t < c.at + 110) || (c.double && t >= c.at + 180 && t < c.at + 290))
  const ripples = click ? (click.double ? [click.at, click.at + 180] : [click.at]) : []
  const visible = t > 150 && t < S.goto + 2500
  return (
    <div ref={ref} className="pointer-events-none absolute top-0 left-0 z-50" style={{ opacity: visible ? 1 : 0 }}>
      {ripples.map((at) => {
        const p = prog(t, at, at + 420)
        if (p <= 0 || p >= 1) return null
        return (
          <span
            key={at}
            className="absolute rounded-full border-2 border-(--d-accent)"
            style={{ width: 36, height: 36, left: -18, top: -18, opacity: 1 - p, transform: `scale(${0.3 + p * 0.9})` }}
          />
        )
      })}
      <svg
        width="20"
        height="24"
        viewBox="0 0 20 24"
        className="absolute -top-0.5 -left-0.5 drop-shadow-[0_2px_3px_rgb(0_0_0/0.35)] transition-transform duration-75"
        style={{ transform: pressed ? 'scale(0.86)' : undefined, transformOrigin: '2px 2px' }}
      >
        <path d="M2 1.5v17.2l4.3-4.1 2.8 6.6 3-1.3-2.8-6.5h6.1z" fill="#111" stroke="#fff" strokeWidth="1.4" strokeLinejoin="round" />
      </svg>
    </div>
  )
})

function Keycaps({ t }: { t: number }) {
  const cap = T.keycaps.find((k) => t >= k.at && t < k.at + k.dur)
  if (!cap) return null
  const o = fade(t, cap.at, cap.at + cap.dur, 120, 220)
  return (
    <div
      className="pointer-events-none absolute bottom-16 left-1/2 z-40 flex items-center gap-3 rounded-2xl bg-black/75 px-4 py-3 text-white shadow-2xl backdrop-blur-md"
      style={{ opacity: o, transform: `translate(-50%, ${(1 - easeOut(prog(t, cap.at, cap.at + 200))) * 10}px)` }}
    >
      <span className="flex gap-1.5">
        {cap.keys.map((k) => (
          <kbd
            key={k}
            className="flex h-9 min-w-9 items-center justify-center rounded-lg border border-white/25 border-b-[3px] bg-white/10 px-2 font-sans text-[18px] font-medium"
          >
            {k}
          </kbd>
        ))}
      </span>
      <span className="text-[15px] font-medium">{cap.label}</span>
    </div>
  )
}

// ===============================================================================================================
// The window

function AppWindow({ t }: { t: number }) {
  const sidebarW = 268 * (1 - ease(prog(t, T.SQL.openConsole, T.SQL.openConsole + 380)))
  const agentW = 452 * ease(prog(t, T.AGENT.open, T.AGENT.open + 420))
  const consoleOpen = t >= T.SQL.openConsole
  const tableOpen = t >= T.TABLE_OPEN
  const active: 'none' | 'orders' | 'console' = consoleOpen ? 'console' : tableOpen ? 'orders' : 'none'
  const intro = prog(t, 0, 350)

  return (
    <div className="absolute inset-0 flex flex-col bg-(--d-bg) font-sans text-[13px] text-(--d-fg)" style={{ opacity: 0.2 + 0.8 * intro }}>
      <TitleBar active={active} />
      <div className="flex min-h-0 flex-1">
        <LeftRail />
        <div className="shrink-0 overflow-hidden" style={{ width: sidebarW }}>
          <Sidebar t={t} />
        </div>
        <div className="relative mr-1 flex min-w-0 flex-1 flex-col overflow-hidden rounded-[10px] border border-(--d-rule) bg-(--d-panel)">
          <Tabs t={t} active={active} />
          {active === 'none' && <EmptyMain />}
          {active === 'orders' && <TableView t={t} />}
          {active === 'console' && <ConsoleView t={t} />}
          <Toasts t={t} />
        </div>
        <div className="shrink-0 overflow-hidden" style={{ width: agentW }}>
          <AgentPanel t={t} />
        </div>
        <RightRail t={t} />
      </div>
      <StatusBar active={active} />
      <Palette t={t} />
      <EndCard t={t} />
    </div>
  )
}

function Mono({ c, color, className }: { c: string; color: string; className?: string }) {
  return (
    <span
      className={cn('inline-flex size-[18px] shrink-0 items-center justify-center rounded-[4px] text-[8.5px] font-bold text-white', className)}
      style={{ background: color }}
    >
      {c}
    </span>
  )
}

const envTone: Record<string, string> = {
  LOCAL: 'bg-(--d-hover) text-(--d-muted)',
  STG: 'bg-[#d9822b]/15 text-[#d9822b]',
  PROD: 'bg-(--d-danger-bg) text-(--d-danger)',
  DEV: 'bg-(--d-success-bg) text-(--d-success)',
}

function Env({ env }: { env: string }) {
  return <span className={cn('rounded-[4px] px-1.5 py-px text-[10px] font-semibold tracking-wide', envTone[env])}>{env}</span>
}

function TitleBar({ active }: { active: 'none' | 'orders' | 'console' }) {
  return (
    <div className="relative flex h-[38px] shrink-0 items-center px-4">
      <div className="flex gap-2">
        <span className="size-3 rounded-full bg-[#ff5f57]" />
        <span className="size-3 rounded-full bg-[#febc2e]" />
        <span className="size-3 rounded-full bg-[#28c840]" />
      </div>
      <div className="ml-6 flex items-center gap-2 font-medium">
        <Mono c="SL" color="#6b7a8f" />
        shop (local)
        <Env env="LOCAL" />
        <ChevronDown className="size-3.5 text-(--d-muted)" />
      </div>
      <div className="absolute left-1/2 -translate-x-1/2 text-(--d-muted)">{active === 'orders' ? 'orders' : 'shop (local)'}</div>
      <div className="ml-auto flex items-center gap-4 text-(--d-muted)">
        <span data-target="new-console">
          <SquareTerminal className="size-4" />
        </span>
        <Play className="size-4 text-(--d-success)" />
        <Settings className="size-4" />
      </div>
    </div>
  )
}

function LeftRail() {
  return (
    <div className="flex w-[52px] shrink-0 flex-col items-center gap-4 pt-2 pb-3 text-(--d-muted)">
      <span className="flex size-9 items-center justify-center rounded-lg bg-(--d-accent) text-white">
        <Database className="size-4" />
      </span>
      <Clock className="size-[18px]" />
      <Bookmark className="size-[18px] fill-[#e8b10e] text-[#e8b10e]" />
      <span className="h-px w-6 bg-(--d-rule)" />
      <Search className="size-[18px]" />
      <Plus className="size-[18px]" />
      <span className="mt-auto" />
      <Keyboard className="size-[18px]" />
      <Settings className="size-[18px]" />
    </div>
  )
}

function RightRail({ t }: { t: number }) {
  const on = t >= T.AGENT.open && t < S.goto + 2200
  return (
    <div className="flex w-[46px] shrink-0 flex-col items-center pt-1.5 text-(--d-muted)">
      <span
        data-target="agent-btn"
        className={cn('flex size-8 items-center justify-center rounded-lg transition-colors', on && 'bg-(--d-accent) text-white')}
      >
        <Sparkles className="size-4" />
      </span>
    </div>
  )
}

function StatusBar({ active }: { active: 'none' | 'orders' | 'console' }) {
  return (
    <div className="flex h-[30px] shrink-0 items-center gap-2 px-4 text-[12px] text-(--d-muted)">
      <Mono c="SL" color="#6b7a8f" className="size-4 text-[7.5px]" />
      shop (local)
      {active !== 'none' && (
        <>
          <ChevronRight className="size-3" />
          <span className="text-(--d-fg)">{active === 'orders' ? 'orders' : 'console'}</span>
        </>
      )}
      <span className="ml-auto flex items-center gap-4">
        <span>110%</span>
        <span>Ready</span>
        <span>SQLite</span>
      </span>
    </div>
  )
}

// ---------------------------------------------------------------------------------------------------------------
// Sidebar

function Sidebar({ t }: { t: number }) {
  const expanded = t >= T.CONNECT_CLICK
  const connecting = t >= T.CONNECT_CLICK && t < T.TABLES_SHOWN
  const ordersSelected = t >= T.TABLE_OPEN
  return (
    <div className="mr-2 flex h-full w-[260px] flex-col rounded-[10px] border border-(--d-rule) bg-(--d-panel)">
      <div className="flex items-center justify-between px-3.5 pt-3 pb-2.5">
        <span className="text-[14px] font-semibold">Connections</span>
        <Plus className="size-4 text-(--d-muted)" />
      </div>
      <div className="mx-3 flex h-8 items-center gap-2 rounded-md border border-(--d-rule) px-2.5 text-(--d-faint)">
        <Search className="size-3.5" />
        Search connections and tables
      </div>
      <div className="mt-2.5 px-1.5">
        {T.connections.map((c) => (
          <React.Fragment key={c.id}>
            <div
              data-target={c.id === 'shop' ? 'conn-shop' : undefined}
              className={cn('flex h-[30px] items-center gap-1.5 rounded-md px-1.5', c.id === 'shop' && expanded && !ordersSelected && 'bg-(--d-hover)')}
            >
              {c.id === 'shop' && connecting ? (
                <Loader2 className="size-3.5 animate-spin text-(--d-muted)" />
              ) : (
                <ChevronRight
                  className={cn('size-3.5 text-(--d-muted) transition-transform', c.id === 'shop' && expanded && 'rotate-90')}
                />
              )}
              <span className="relative">
                <Mono c={c.mono} color={c.color} />
                {c.id === 'shop' && t >= T.TABLES_SHOWN && (
                  <span className="absolute -right-0.5 -bottom-0.5 size-[7px] rounded-full border border-(--d-panel) bg-[#28c840]" />
                )}
              </span>
              <span className="truncate">{c.name}</span>
              <Env env={c.env} />
              <span className="truncate text-[12px] text-(--d-muted)">{c.engine}</span>
            </div>
            {c.id === 'shop' &&
              expanded &&
              T.shopTables.map((tb, i) => {
                const o = prog(t, T.TABLES_SHOWN + i * 60, T.TABLES_SHOWN + i * 60 + 180)
                if (o <= 0) return null
                const sel = tb.name === 'orders' && ordersSelected
                return (
                  <div
                    key={tb.name}
                    data-target={`node-${tb.name}`}
                    className={cn('flex h-[28px] items-center gap-2 rounded-md pr-2 pl-9', sel && 'bg-(--d-sel)')}
                    style={{ opacity: o, transform: `translateX(${(1 - o) * -6}px)` }}
                  >
                    <Table2 className="size-3.5 text-(--d-muted)" />
                    <span className={cn(sel && 'font-medium')}>{tb.name}</span>
                    <span className="ml-auto font-mono text-[11px] text-(--d-faint)">{tb.rows}</span>
                  </div>
                )
              })}
          </React.Fragment>
        ))}
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------------------------------------------
// Main area

function Tabs({ t, active }: { t: number; active: 'none' | 'orders' | 'console' }) {
  const tabs: { id: 'orders' | 'console'; label: string; icon: React.ReactNode; from: number }[] = [
    { id: 'orders', label: 'orders', icon: <Table2 className="size-4" />, from: T.TABLE_OPEN },
    { id: 'console', label: 'shop (local)', icon: <SquareTerminal className="size-4" />, from: T.SQL.openConsole },
  ]
  return (
    <div className="flex h-[46px] shrink-0 items-center gap-1 border-b border-(--d-rule) px-2">
      {tabs.map((tab) => {
        if (t < tab.from) return null
        const o = prog(t, tab.from, tab.from + 180)
        const on = active === tab.id
        return (
          <div
            key={tab.id}
            className={cn(
              'flex h-[34px] items-center gap-2 rounded-lg border px-3 text-[14px]',
              on ? 'border-(--d-sel-border) bg-(--d-sel)' : 'border-transparent text-(--d-muted)',
            )}
            style={{ opacity: o, transform: `translateY(${(1 - o) * 4}px)` }}
          >
            {tab.icon}
            {tab.label}
            {on && <span className="ml-1 text-[13px] text-(--d-muted)">×</span>}
          </div>
        )
      })}
    </div>
  )
}

function EmptyMain() {
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-3 text-(--d-muted)">
      <Database className="size-8 text-(--d-faint)" strokeWidth={1.4} />
      <p className="text-[14px]">Open a table from the sidebar</p>
      <p className="flex items-center gap-1.5 text-[12px]">
        or press <Kbd>⌘</Kbd>
        <Kbd>P</Kbd> to go to anything
      </p>
    </div>
  )
}

function Kbd({ children }: { children: React.ReactNode }) {
  return (
    <kbd className="inline-flex h-5 min-w-5 items-center justify-center rounded border border-(--d-rule-strong) border-b-2 px-1 font-sans text-[11px]">
      {children}
    </kbd>
  )
}

function Toasts({ t }: { t: number }) {
  const toast = T.toasts.find((x) => t >= x.from && t < x.to)
  if (!toast) return null
  const o = fade(t, toast.from, toast.to, 180, 260)
  return (
    <div
      className="absolute right-4 bottom-4 z-20 flex items-center gap-2 rounded-lg border border-(--d-rule) bg-(--d-panel) px-3 py-2 shadow-(--d-shadow)"
      style={{ opacity: o, transform: `translateY(${(1 - o) * 8}px)` }}
    >
      <span className="flex size-4 items-center justify-center rounded-full bg-(--d-success) text-(--d-panel)">
        <Check className="size-3" strokeWidth={3} />
      </span>
      {toast.text}
    </div>
  )
}

// ---------------------------------------------------------------------------------------------------------------
// Table view: toolbar, filter bar, grid

function TableView({ t }: { t: number }) {
  const filtered = t >= T.FILTER.apply
  const pending = (t >= T.EDIT.total.close ? 1 : 0) + (t >= T.EDIT.status.close ? 1 : 0)
  const committed = t >= T.EDIT.commit
  const showPending = pending > 0 && !committed
  return (
    <div className="relative flex min-h-0 flex-1 flex-col">
      <div className="flex h-[42px] shrink-0 items-center gap-1 border-b border-(--d-rule) px-2 text-(--d-muted)">
        <span className="rounded-md bg-(--d-sel) px-2.5 py-1 font-medium text-(--d-fg)">Data</span>
        <span className="px-2.5 py-1">Structure</span>
        <span className="mx-1.5 h-5 w-px bg-(--d-rule)" />
        <ChevronsLeft className="size-4 opacity-50" />
        <ChevronLeft className="size-4 opacity-50" />
        <span className="px-1 font-mono text-[12.5px] text-(--d-fg)">{t >= T.FILTER.outEnd ? '1–5 of 5' : '1–500 of ?'}</span>
        <ChevronRight className="size-4" />
        <span className="ml-1 flex h-7 items-center gap-6 rounded-md border border-(--d-rule) px-2 text-(--d-fg)">
          500 rows <ChevronDown className="size-3.5 text-(--d-muted)" />
        </span>
        <RefreshCw className="mx-1.5 size-4" />
        <span className="h-5 w-px bg-(--d-rule)" />
        <Plus className="mx-1 size-4" />
        <Minus className="mx-1 size-4" />
        <Undo2 className={cn('mx-1 size-4', showPending && 'text-(--d-fg)')} />
        <Redo2 className="mx-1 size-4 opacity-50" />
        {showPending && (
          <span
            data-target="pending"
            className="ml-2 flex h-7 items-center gap-1.5 rounded-md bg-(--d-pending) px-2.5 font-medium text-(--d-pending-fg)"
            style={{ opacity: prog(t, T.EDIT.total.close, T.EDIT.total.close + 160) }}
          >
            <span className="size-1.5 rounded-full bg-current" />
            {pending} pending
          </span>
        )}
        <span className="ml-auto flex items-center gap-0.5 rounded-md bg-(--d-panel-2) p-0.5">
          <span className="rounded bg-(--d-sel) px-2.5 py-0.5 font-medium text-(--d-fg)">Grid</span>
          <span className="px-2.5 py-0.5">JSON</span>
        </span>
        <PanelRight className="mx-1.5 size-4" />
        <Download className="mx-1 size-4" />
      </div>
      <FilterBar t={t} />
      <Grid t={t} filtered={filtered} />
      <ReviewPopover t={t} />
    </div>
  )
}

function FilterBar({ t }: { t: number }) {
  const F = T.FILTER
  const chip1 = t >= F.chip1
  const chip2 = t >= F.chip2
  const applied = t >= F.apply
  const v1 = typed(F.chip1Value.text, t, F.chip1Value.start, F.chip1Value.ms)
  const v2 = typed(F.chip2Value.text, t, F.chip2Value.start, F.chip2Value.ms)
  const typing1 = t >= F.chip1Value.start && t < F.chip2
  const typing2 = t >= F.chip2 && t < F.apply - 300
  const popover = t >= F.popover[0] && t < F.popover[1]
  return (
    <div className="relative flex h-[44px] shrink-0 items-center gap-2 border-b border-(--d-rule) px-3">
      <Filter className={cn('size-4', applied ? 'text-(--d-accent)' : 'text-(--d-muted)')} />
      <span className="flex items-center gap-0.5 text-(--d-muted)">
        <span className="rounded-md bg-(--d-sel) px-2.5 py-1 font-medium text-(--d-fg)">Builder</span>
        <span className="px-2.5 py-1">SQL</span>
        <span className="px-2.5 py-1">Columns</span>
      </span>
      <span className="mx-1 h-5 w-px bg-(--d-rule)" />
      {chip1 && <Chip col="status" op="=" value={v1 ? `'${v1}${typing1 ? '' : "'"}` : ''} caret={typing1} t={t} from={F.chip1} />}
      {chip2 && <Chip col="total" op=">" value={v2} caret={typing2} t={t} from={F.chip2} />}
      <span data-target="add-condition" className="relative font-medium text-(--d-accent)">
        {chip1 ? '+ Add' : 'Add condition'}
        {popover && <ColumnPopover t={t} />}
      </span>
      {!chip1 && <span className="text-(--d-muted)">drag a column here, or ⌥⌘F on a cell to filter by its value</span>}
      <span
        data-target="apply"
        className={cn(
          'ml-auto rounded-md border px-4 py-1 font-medium transition-colors',
          chip2 && !applied
            ? 'border-(--d-accent) bg-(--d-accent) text-white'
            : 'border-(--d-rule) text-(--d-muted)',
        )}
      >
        Apply
      </span>
    </div>
  )
}

function Chip({ col, op, value, caret, t, from }: { col: string; op: string; value: string; caret: boolean; t: number; from: number }) {
  const o = easeOut(prog(t, from, from + 180))
  return (
    <span
      className={cn(
        'flex h-7 items-center overflow-hidden rounded-md border bg-(--d-panel) text-[12.5px]',
        caret ? 'border-(--d-accent) ring-2 ring-(--d-accent)/20' : 'border-(--d-rule-strong)',
      )}
      style={{ opacity: o, transform: `scale(${0.92 + 0.08 * o})` }}
    >
      <span className="border-r border-(--d-rule) px-2 font-medium">{col}</span>
      <span className="border-r border-(--d-rule) px-2 font-mono text-(--d-muted)">{op}</span>
      <span className="min-w-8 px-2 font-mono">
        {value}
        {caret && <Caret />}
      </span>
    </span>
  )
}

function Caret() {
  return <span className="ml-px inline-block h-[1.05em] w-[1.5px] translate-y-[2px] animate-pulse bg-(--d-accent)" />
}

function ColumnPopover({ t }: { t: number }) {
  const o = prog(t, T.FILTER.popover[0], T.FILTER.popover[0] + 140)
  const hover = t >= T.FILTER.hoverStatus ? 2 : -1
  return (
    <div
      className="absolute top-7 left-0 z-30 w-56 rounded-lg border border-(--d-rule) bg-(--d-panel) p-1 font-normal text-(--d-fg) shadow-(--d-shadow)"
      style={{ opacity: o }}
    >
      <div className="px-2 py-1 text-[11px] text-(--d-muted)">Filter by column</div>
      {T.orderColumns.map((c, i) => (
        <div
          key={c.name}
          data-target={`col-${c.name}`}
          className={cn('flex items-center justify-between rounded-md px-2 py-1', i === hover && 'bg-(--d-accent) text-white')}
        >
          <span>{c.name}</span>
          <span className={cn('font-mono text-[11px]', i === hover ? 'text-white/80' : 'text-(--d-muted)')}>{c.type}</span>
        </div>
      ))}
    </div>
  )
}

const gridCols = '52px 150px 170px 150px 120px minmax(190px,1fr)'

function Grid({ t, filtered }: { t: number; filtered: boolean }) {
  const F = T.FILTER
  let rows = T.orders
  let rowOpacity = (i: number) => prog(t, T.ROWS_IN + i * 35, T.ROWS_IN + i * 35 + 200)
  if (filtered) {
    if (t < F.outEnd) {
      const out = 1 - prog(t, F.apply, F.outEnd)
      rowOpacity = () => out
    } else {
      rows = T.filteredOrders
      rowOpacity = (i) => prog(t, F.outEnd + i * 60, F.outEnd + i * 60 + 200)
    }
  }
  return (
    <div className="relative min-h-0 flex-1 overflow-hidden font-mono text-[13px]">
      <div className="grid h-[34px] items-center border-b border-(--d-rule) font-sans" style={{ gridTemplateColumns: gridCols }}>
        <span />
        {T.orderColumns.map((c) => (
          <span key={c.name} className="flex h-full items-center gap-1.5 border-l border-(--d-rule) px-3">
            <span className="font-semibold">{c.name}</span>
            <span className="text-(--d-muted)">{c.type}</span>
          </span>
        ))}
      </div>
      {rows.map((r, i) => (
        <GridRow key={r.id} t={t} row={r} index={i} opacity={rowOpacity(i)} />
      ))}
      <span data-target="grid-rest" className="absolute right-[38%] bottom-[18%] size-1" />
    </div>
  )
}

function GridRow({ t, row, index, opacity }: { t: number; row: T.Order; index: number; opacity: number }) {
  if (opacity <= 0) return null
  const E = T.EDIT
  const cell = (field: 'total' | 'status', value: string) => {
    const edit = field === 'total' ? E.total : E.status
    if (row.id !== edit.id || t < edit.open) return { value, state: 'idle' as const }
    if (t < edit.close) {
      const v = t < edit.typeStart ? edit.from : typed(edit.text, t, edit.typeStart, edit.ms)
      return { value: v, state: t < edit.typeStart ? ('selected' as const) : ('editing' as const) }
    }
    return { value: edit.text, state: t < E.commit ? ('pending' as const) : ('idle' as const) }
  }
  const total = cell('total', row.total)
  const status = cell('status', row.status)
  const pendingRow = total.state === 'pending' || status.state === 'pending'
  const edited = row.id === E.total.id || row.id === E.status.id
  const flash = edited ? 1 - prog(t, E.commit, E.commit + 900) : 0
  const showFlash = edited && t >= E.commit && flash > 0

  return (
    <div
      className="relative grid h-[31px] items-center border-b border-(--d-rule)"
      style={{ gridTemplateColumns: gridCols, opacity, transform: `translateY(${(1 - opacity) * 4}px)` }}
    >
      {showFlash && <span className="absolute inset-0 bg-(--d-success)" style={{ opacity: flash * 0.22 }} />}
      <span className={cn('relative pr-3 text-right text-(--d-muted)', pendingRow && 'text-(--d-pending-fg)')}>
        {pendingRow && <span className="absolute top-1/2 left-1.5 size-1.5 -translate-y-1/2 rounded-full bg-(--d-pending-fg)" />}
        {index + 1}
      </span>
      <span className="relative h-full border-l border-(--d-rule) px-3 text-right leading-[31px]">{row.id}</span>
      <span className="flex h-full items-center justify-end gap-1.5 border-l border-(--d-rule) px-3">
        {row.customer}
        <ChevronRight className="size-3 text-(--d-muted)" />
      </span>
      <EditableCell target={`cell-status-${row.id}`} {...status} />
      <EditableCell target={`cell-total-${row.id}`} {...total} right />
      <span className="h-full border-l border-(--d-rule) px-3 leading-[31px]">{row.created}</span>
    </div>
  )
}

function EditableCell({
  target,
  value,
  state,
  right,
}: {
  target: string
  value: string
  state: 'idle' | 'selected' | 'editing' | 'pending'
  right?: boolean
}) {
  return (
    <span
      data-target={target}
      className={cn(
        'relative h-full border-l border-(--d-rule) px-3 leading-[31px]',
        right && 'text-right',
        state === 'pending' && 'bg-(--d-pending) text-(--d-pending-fg)',
      )}
    >
      {(state === 'selected' || state === 'editing') && (
        <span className="absolute inset-[2px] rounded-[3px] border-2 border-(--d-accent) bg-(--d-panel)" />
      )}
      <span className="relative">
        {state === 'selected' ? <span className="bg-(--d-accent) text-white">{value}</span> : value}
        {state === 'editing' && <Caret />}
      </span>
    </span>
  )
}

function ReviewPopover({ t }: { t: number }) {
  const [a, b] = T.EDIT.review
  if (t < a || t >= b + 200) return null
  const o = fade(t, a, b + 200, 160, 200)
  return (
    <div
      className="absolute top-[46px] right-24 z-30 w-[470px] rounded-xl border border-(--d-rule) bg-(--d-panel) shadow-(--d-shadow)"
      style={{ opacity: o, transform: `translateY(${(1 - easeOut(prog(t, a, a + 200))) * -6}px)` }}
    >
      <div className="flex items-center justify-between border-b border-(--d-rule) px-4 py-2.5">
        <span className="font-semibold">Review 2 changes</span>
        <span className="text-[12px] text-(--d-muted)">orders · shop (local)</span>
      </div>
      <pre className="px-4 py-3 font-mono text-[12.5px] leading-[1.7]">
        <SqlLine line="UPDATE orders SET total = 41.24 WHERE id = 259020;" />
        {'\n'}
        <SqlLine line="UPDATE orders SET status = 'paid' WHERE id = 259024;" />
      </pre>
      <div className="flex items-center gap-2 border-t border-(--d-rule) px-4 py-2.5">
        <span className="text-[12px] text-(--d-muted)">Runs in one transaction</span>
        <span className="ml-auto rounded-md border border-(--d-rule) px-3 py-1">Discard</span>
        <span data-target="submit" className="rounded-md bg-(--d-accent) px-3 py-1 font-medium text-white">
          Submit
        </span>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------------------------------------------
// SQL console

const KW = new Set(
  'SELECT FROM JOIN ON WHERE AND OR GROUP BY ORDER DESC ASC LIMIT AS CREATE VIEW NOT EXISTS UPDATE SET IN'.split(' '),
)

function SqlLine({ line }: { line: string }) {
  if (line.trimStart().startsWith('--')) return <span className="text-(--d-com) italic">{line}</span>
  const parts: React.ReactNode[] = []
  const re = /('[^']*'?)|(\b\d+(?:\.\d+)?\b)|([A-Za-z_][A-Za-z0-9_]*)|(\s+|.)/g
  let m: RegExpExecArray | null
  let i = 0
  while ((m = re.exec(line))) {
    const [s, str, num, word] = m
    if (str) parts.push(<span key={i++} className="text-(--d-str)">{s}</span>)
    else if (num) parts.push(<span key={i++} className="text-(--d-num)">{s}</span>)
    else if (word && KW.has(word)) parts.push(<span key={i++} className="text-(--d-kw)">{s}</span>)
    else if (word && line[m.index + s.length] === '(') parts.push(<span key={i++} className="text-(--d-fn)">{s}</span>)
    else parts.push(s)
  }
  return <>{parts}</>
}

function ConsoleView({ t }: { t: number }) {
  const { text, popup } = T.editorAt(t)
  const lines = text.split('\n')
  const caretLine = lines.length - 1
  const caretCol = lines[caretLine].length
  const ran = t >= T.SQL.results
  const running = t >= T.SQL.run && t < T.SQL.results
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex h-[42px] shrink-0 items-center gap-3.5 border-b border-(--d-rule) px-3 text-(--d-muted)">
        <Play className={cn('size-4 text-(--d-success)', running && 'animate-pulse')} />
        <FastForward className="size-4 text-(--d-success)" />
        <Square className="size-3.5" />
        <Eye className="size-4" />
        <Zap className="size-4" />
        <Search className="size-4" />
        <Save className="size-4" />
        <span className="h-5 w-px bg-(--d-rule)" />
        <span className="flex h-7 items-center gap-10 rounded-md border border-(--d-rule) px-2.5 text-(--d-fg)">
          Auto-commit <ChevronDown className="size-3.5 text-(--d-muted)" />
        </span>
        <span className="ml-auto flex items-center gap-2">
          <Env env="LOCAL" /> shop (local) · SQLite
        </span>
      </div>
      <div className="relative z-10 h-[300px] shrink-0 py-2.5 font-mono text-[14px] leading-[23px]">
        {lines.map((line, i) => (
          <div key={i} className={cn('relative flex', i === caretLine && 'bg-(--d-hover)/60')}>
            <span className="w-12 shrink-0 pr-4 text-right text-(--d-faint)">{i + 1}</span>
            <span className="relative whitespace-pre">
              <SqlLine line={line} />
              {i === caretLine && !ran && <Caret />}
              {i === caretLine && popup && <Completion popup={popup} col={caretCol} />}
            </span>
          </div>
        ))}
        <span data-target="editor-rest" className="absolute right-[30%] bottom-10 size-1" />
      </div>
      <Results t={t} ran={ran} />
    </div>
  )
}

function Completion({ popup, col }: { popup: { title: string; items: T.Suggestion[] }; col: number }) {
  return (
    <div
      className="absolute top-[24px] z-30 w-[400px] overflow-hidden rounded-lg border border-(--d-rule) bg-(--d-panel) font-sans text-[13px] shadow-(--d-shadow)"
      style={{ left: `${Math.max(0, col - (col > 0 ? 0 : 0))}ch` }}
    >
      <div className="px-3 pt-2 pb-1 text-[11px] text-(--d-muted)">{popup.title}</div>
      {popup.items.map((item, i) => (
        <div key={item.label} className={cn('mx-1 flex items-center gap-2 rounded-md px-2 py-1', i === 0 && 'bg-(--d-accent) text-white')}>
          <span className="font-mono text-[12.5px]">{item.label}</span>
          <span className={cn('ml-auto text-[11px]', i === 0 ? 'text-white/80' : 'text-(--d-muted)')}>{item.hint}</span>
        </div>
      ))}
      <div className="mt-1 border-t border-(--d-rule) px-3 py-1 text-[11px] text-(--d-muted)">⇥ to accept</div>
    </div>
  )
}

function Results({ t, ran }: { t: number; ran: boolean }) {
  const chart = t >= T.SQL.chart
  const max = Math.max(...T.revenue.map((r) => r.revenue))
  return (
    <div className="relative flex min-h-0 flex-1 flex-col border-t border-(--d-rule)">
      <div className="flex h-[38px] shrink-0 items-end gap-5 px-3 text-[13.5px] text-(--d-muted)">
        <span className={cn('pb-2', !ran && 'border-b-2 border-(--d-accent) text-(--d-fg)')}>Output</span>
        {ran && <span className="border-b-2 border-(--d-accent) pb-2 text-(--d-fg)">Result 1 ×</span>}
        <span className="pb-2">History</span>
      </div>
      <div className="relative min-h-0 flex-1 overflow-hidden border-t border-(--d-rule)">
        {!ran && (
          <div className="flex h-full items-center justify-center gap-1.5 text-(--d-muted)">
            Press <Kbd>⌘</Kbd>
            <Kbd>↵</Kbd> to run the statement under the caret
          </div>
        )}
        {ran && !chart && (
          <div className="font-mono text-[13px]">
            <div
              className="grid h-[32px] items-center border-b border-(--d-rule) font-sans"
              style={{ gridTemplateColumns: '44px 1.3fr 1fr 1fr 1fr' }}
            >
              <span />
              {[
                ['full_name', 'text'],
                ['country', 'text'],
                ['orders', 'integer'],
                ['revenue', 'float'],
              ].map(([n, ty]) => (
                <span key={n} className="flex h-full items-center gap-1.5 border-l border-(--d-rule) px-3">
                  <span className="font-semibold">{n}</span>
                  <span className="text-(--d-muted)">{ty}</span>
                </span>
              ))}
            </div>
            {T.revenue.map((r, i) => {
              const o = prog(t, T.SQL.results + i * 45, T.SQL.results + i * 45 + 180)
              return (
                <div
                  key={r.name}
                  className="grid h-[29px] items-center border-b border-(--d-rule)"
                  style={{ gridTemplateColumns: '44px 1.3fr 1fr 1fr 1fr', opacity: o }}
                >
                  <span className="pr-3 text-right text-(--d-muted)">{i + 1}</span>
                  <span className="border-l border-(--d-rule) px-3 leading-[29px]">{r.name}</span>
                  <span className="border-l border-(--d-rule) px-3 leading-[29px]">{r.country}</span>
                  <span className="border-l border-(--d-rule) px-3 text-right leading-[29px]">{r.orders}</span>
                  <span className="border-l border-(--d-rule) px-3 text-right leading-[29px]">{r.revenue.toFixed(2)}</span>
                </div>
              )
            })}
          </div>
        )}
        {chart && (
          <div className="flex h-full flex-col justify-center gap-[7px] px-6 py-3">
            {T.revenue.map((r, i) => {
              const p = easeOut(prog(t, T.SQL.chart + 80 + i * 55, T.SQL.chart + 80 + i * 55 + 600))
              return (
                <div key={r.name} className="flex items-center gap-3">
                  <span className="w-24 shrink-0 truncate text-right text-[12px] text-(--d-muted)">{r.name}</span>
                  <span className="h-[16px] rounded-[3px] bg-(--d-accent)" style={{ width: `${(r.revenue / max) * 70 * p}%`, opacity: 0.55 + 0.45 * (1 - i / 10) }} />
                  <span className="font-mono text-[11.5px]" style={{ opacity: p }}>
                    {r.revenue.toFixed(2)}
                  </span>
                </div>
              )
            })}
          </div>
        )}
        <span data-target="results-rest" className="absolute top-1/2 right-1/4 size-1" />
      </div>
      <div className="flex h-[38px] shrink-0 items-center gap-2 border-t border-(--d-rule) px-3 text-(--d-muted)">
        <ChevronsLeft className="size-4 opacity-50" />
        <ChevronLeft className="size-4 opacity-50" />
        <span className="font-mono text-[12.5px] text-(--d-fg)">{ran ? '1–50 of 50' : '–'}</span>
        <ChevronRight className="size-4 opacity-50" />
        {ran && <span className="ml-2 text-[12px]">5 ms</span>}
        <span className="ml-auto flex items-center gap-0.5 rounded-md bg-(--d-panel-2) p-0.5">
          <span className={cn('rounded px-2.5 py-0.5', ran && !chart && 'bg-(--d-sel) font-medium text-(--d-fg)')}>Grid</span>
          <span className="px-2.5 py-0.5">JSON</span>
          <span data-target="chart-tab" className={cn('rounded px-2.5 py-0.5', chart && 'bg-(--d-sel) font-medium text-(--d-fg)')}>
            Chart
          </span>
        </span>
        <Pin className="mx-1 size-4" />
        <Download className="size-4" />
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------------------------------------------
// Agent panel

function AgentPanel({ t }: { t: number }) {
  const A = T.AGENT
  const sent = t >= A.send
  const draft = sent ? '' : typed(A.prompt.text, t, A.prompt.start, A.prompt.ms)
  const focused = t >= A.prompt.start - 100 && !sent
  const reading = t >= A.readStart
  const read = t >= A.readDone
  const answer = typed(A.answer.text, t, A.answer.start, A.answer.ms)
  const codeLines = Math.max(0, Math.floor((t - A.block) / A.codeMsPerLine))
  const tooltip = fade(t, A.tooltip, S.goto + 200, 180, 200)

  return (
    <div className="ml-1 flex h-full w-[448px] flex-col rounded-[10px] border border-(--d-rule) bg-(--d-panel)">
      <div className="flex h-[46px] shrink-0 items-center gap-3 border-b border-(--d-rule) px-4 text-(--d-muted)">
        <span className="font-semibold text-(--d-fg)">{sent ? 'Customers lost after a refund' : 'New chat'}</span>
        <History className="ml-auto size-4" />
        <Plus className="size-4" />
        <Minus className="size-4" />
      </div>
      <div className="min-h-0 flex-1 space-y-4 overflow-hidden px-4 py-4">
        {sent && (
          <div
            className="ml-10 rounded-xl bg-(--d-panel-2) px-3.5 py-2.5 leading-relaxed"
            style={{ opacity: prog(t, A.send, A.send + 200), transform: `translateY(${(1 - easeOut(prog(t, A.send, A.send + 250))) * 20}px)` }}
          >
            {A.prompt.text}
          </div>
        )}
        {reading && (
          <div className="flex items-center gap-2 text-(--d-muted)">
            {read ? <Check className="size-3.5 text-(--d-success)" /> : <Loader2 className="size-3.5 animate-spin" />}
            {read ? 'Read schema of customers, orders' : 'Reading schema…'}
            <span className="ml-auto text-[11px] text-(--d-faint)">no rows sent</span>
          </div>
        )}
        {answer && <p className="leading-relaxed">{answer}</p>}
        {t >= A.block && (
          <div
            className="relative rounded-lg border border-(--d-danger)/60 bg-(--d-danger-bg)/50 px-3 py-2.5"
            style={{ opacity: prog(t, A.block, A.block + 200) }}
          >
            <div className="flex items-center text-[12px] text-(--d-muted)">
              sql
              <span className="ml-auto flex items-center gap-3">
                <Copy className="size-3.5" />
                <SquareTerminal className="size-3.5" />
                <span data-target="agent-run">
                  <Play className="size-3.5 text-(--d-success)" />
                </span>
              </span>
            </div>
            <div className="mt-1 flex items-center gap-1.5 font-medium text-(--d-danger)">
              <TriangleAlert className="size-3.5" /> Can change data or schema: CREATE
            </div>
            <pre className="mt-2 font-mono text-[12px] leading-[1.6]">
              {T.agentSql.slice(0, codeLines).map((l, i) => (
                <div key={i}>
                  <SqlLine line={l} />
                </div>
              ))}
            </pre>
            {tooltip > 0 && (
              <div
                className="absolute right-0 -bottom-9 rounded-md bg-(--d-fg) px-2.5 py-1 text-[12px] whitespace-nowrap text-(--d-panel) shadow-lg"
                style={{ opacity: tooltip }}
              >
                Nothing runs until you press run
              </div>
            )}
          </div>
        )}
      </div>
      <div className="m-3 rounded-xl border border-(--d-rule) p-3" data-target="agent-input">
        <div className={cn('min-h-[44px] leading-relaxed', !draft && 'text-(--d-faint)')}>
          {draft || 'Ask anything: @ mentions tables, tabs and saved queries'}
          {focused && draft && <Caret />}
        </div>
        <div className="mt-2 flex items-center gap-3 text-[12px] text-(--d-muted)">
          <span className="flex items-center gap-1">
            <Sparkles className="size-3.5" /> Claude Code <ChevronDown className="size-3" />
          </span>
          <span className="flex items-center gap-1">
            Default model <ChevronDown className="size-3" />
          </span>
          <span
            className={cn(
              'ml-auto flex size-6 items-center justify-center rounded-full',
              draft ? 'bg-(--d-accent) text-white' : 'bg-(--d-panel-2)',
            )}
          >
            <ArrowUp className="size-3.5" />
          </span>
        </div>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------------------------------------------
// Go to anything, and the end card

const paletteIcon = {
  table: <Table2 className="size-4" />,
  query: <Bookmark className="size-4" />,
  action: <GitCompare className="size-4" />,
  conn: <Database className="size-4" />,
  view: <Eye className="size-4" />,
} as const

function Palette({ t }: { t: number }) {
  const G = T.GOTO
  if (t < G.open || t >= G.close + 200) return null
  const o = fade(t, G.open, G.close + 200, 140, 200)
  const q = typed(G.query.text, t, G.query.start, G.query.ms)
  const items = q.length >= 2 ? T.paletteResults : T.paletteRecent
  return (
    <div className="absolute inset-0 z-30 bg-black/20" style={{ opacity: o }}>
      <div
        className="absolute top-[110px] left-1/2 w-[600px] -translate-x-1/2 overflow-hidden rounded-xl border border-(--d-rule) bg-(--d-panel) shadow-(--d-shadow)"
        style={{ transform: `translateX(-50%) scale(${0.97 + 0.03 * easeOut(prog(t, G.open, G.open + 200))})` }}
      >
        <div className="flex h-12 items-center gap-2.5 border-b border-(--d-rule) px-4 text-[15px]">
          <Search className="size-4 text-(--d-muted)" />
          {q ? (
            <span>
              {q}
              <Caret />
            </span>
          ) : (
            <span className="text-(--d-faint)">Go to tables, tabs, saved queries and actions</span>
          )}
        </div>
        <div className="p-1.5" data-target="palette-rest">
          <div className="px-2.5 pt-1 pb-1.5 text-[11px] text-(--d-muted)">{q.length >= 2 ? 'Best matches' : 'Recent'}</div>
          {items.map((it, i) => (
            <div key={it.label} className={cn('flex h-9 items-center gap-2.5 rounded-lg px-2.5', i === 0 && 'bg-(--d-accent) text-white')}>
              <span className={cn(i === 0 ? 'text-white' : 'text-(--d-muted)')}>{paletteIcon[it.kind as keyof typeof paletteIcon]}</span>
              <span>
                {q.length >= 2 && it.label.toLowerCase().startsWith(q) ? (
                  <>
                    <span className="font-semibold">{it.label.slice(0, q.length)}</span>
                    {it.label.slice(q.length)}
                  </>
                ) : (
                  it.label
                )}
              </span>
              <span className={cn('ml-auto text-[12px]', i === 0 ? 'text-white/80' : 'text-(--d-muted)')}>{it.hint}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

function EndCard({ t }: { t: number }) {
  const from = T.GOTO.endCard
  if (t < from) return null
  const o = fade(t, from, T.LOOP, 500, 450)
  const rise = (d: number) => ({
    opacity: prog(t, from + d, from + d + 400),
    transform: `translateY(${(1 - easeOut(prog(t, from + d, from + d + 500))) * 14}px)`,
  })
  return (
    <div className="absolute inset-0 z-40 flex flex-col items-center justify-center bg-(--d-bg)/85 backdrop-blur-md" style={{ opacity: o }}>
      <img src="/logo.png" alt="" width={88} height={88} className="size-[88px] rounded-[20px] shadow-xl" style={rise(0)} />
      <p className="mt-6 text-[44px] font-semibold tracking-[-0.03em]" style={rise(120)}>
        TableGrip
      </p>
      <p className="mt-2 text-[19px] text-(--d-muted)" style={rise(240)}>
        Free and open source. Every feature, on every database.
      </p>
      <div className="mt-7 flex flex-wrap justify-center gap-2" style={rise(380)}>
        {['PostgreSQL', 'MySQL', 'SQL Server', 'SQLite', 'MongoDB', 'Redis', 'ClickHouse', '+ more'].map((d) => (
          <span key={d} className="rounded-full border border-(--d-rule) bg-(--d-panel) px-3 py-1 text-[13px]">
            {d}
          </span>
        ))}
      </div>
      <p className="mt-7 font-mono text-[13px] text-(--d-muted)" style={rise(520)}>
        macOS · Windows · Linux
      </p>
    </div>
  )
}
