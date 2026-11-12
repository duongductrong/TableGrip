/**
 * The script of the hero's product demo. Everything on screen is a pure function of one clock, `t` (ms into the
 * loop), so the demo can be paused, scrubbed and jumped to a chapter without any hidden state.
 */

/** The demo is laid out at this size and scaled to fit, so it looks the same at every width. */
export const W = 1280
export const H = 800

/** When each chapter starts. */
export const S = {
  welcome: 0,
  connect: 4000,
  filter: 9200,
  edit: 14800,
  sql: 21200,
  agent: 33000,
  goto: 42000,
  end: 48000,
} as const

export const LOOP = S.end

export const chapters = [
  { id: 'welcome', start: S.welcome, title: 'Welcome', caption: 'Start from a recent connection' },
  { id: 'connect', start: S.connect, title: 'Browse', caption: 'Expand the connection and open a table' },
  { id: 'filter', start: S.filter, title: 'Filter', caption: 'Typed filters, no SQL required' },
  { id: 'edit', start: S.edit, title: 'Edit', caption: 'Edit inline, review, submit in one transaction' },
  { id: 'sql', start: S.sql, title: 'SQL', caption: 'Completion from your schema and your data' },
  { id: 'agent', start: S.agent, title: 'AI agent', caption: 'Ask in plain words, you decide what runs' },
  { id: 'goto', start: S.goto, title: 'Go to anything', caption: '⌘P to any table, query or action' },
] as const

// ---------------------------------------------------------------------------------------------------------------
// Helpers

export const clamp = (v: number, lo = 0, hi = 1) => Math.min(hi, Math.max(lo, v))
/** 0 before `a`, 1 after `b`, linear between. */
export const prog = (t: number, a: number, b: number) => clamp((t - a) / (b - a))
export const ease = (p: number) => (p < 0.5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2)
export const easeOut = (p: number) => 1 - Math.pow(1 - p, 3)
/** Opacity of something shown between `a` and `b`, fading in and out. */
export const fade = (t: number, a: number, b: number, inMs = 160, outMs = 200) =>
  Math.min(prog(t, a, a + inMs), 1 - prog(t, b - outMs, b))
/** The part of `text` typed by time `t`, one character every `ms`. */
export const typed = (text: string, t: number, start: number, ms = 45) =>
  text.slice(0, Math.max(0, Math.floor((t - start) / ms)))
export const typingDone = (text: string, start: number, ms = 45) => start + text.length * ms

// ---------------------------------------------------------------------------------------------------------------
// The pointer: each move ends at `at`, on the element tagged data-target={to}.

type Move = { at: number; to: string; dur: number }

const C = S.connect
const F = S.filter
const E = S.edit
const Q = S.sql
const A = S.agent
const G = S.goto

export const moves: Move[] = [
  { at: 1500, to: 'welcome-shop', dur: 900 },
  { at: 2900, to: 'welcome-connect', dur: 800 },

  { at: C + 1200, to: 'conn-shop', dur: 800 },
  { at: C + 2600, to: 'node-orders', dur: 700 },
  { at: C + 4300, to: 'grid-rest', dur: 1000 },

  { at: F + 600, to: 'add-condition', dur: 600 },
  { at: F + 1150, to: 'col-status', dur: 350 },
  { at: F + 2300, to: 'add-condition', dur: 500 },
  { at: F + 3600, to: 'apply', dur: 600 },
  { at: F + 5000, to: 'grid-rest', dur: 900 },

  { at: E + 600, to: 'cell-total-259020', dur: 600 },
  { at: E + 2300, to: 'cell-status-259024', dur: 600 },
  { at: E + 4800, to: 'submit', dur: 600 },
  { at: E + 6200, to: 'grid-rest', dur: 900 },

  { at: Q + 500, to: 'new-console', dur: 500 },
  { at: Q + 1050, to: 'editor-rest', dur: 450 },
  { at: Q + 10100, to: 'chart-tab', dur: 600 },
  { at: Q + 11600, to: 'results-rest', dur: 800 },

  { at: A + 500, to: 'agent-btn', dur: 500 },
  { at: A + 1500, to: 'agent-input', dur: 500 },
  { at: A + 7700, to: 'agent-run', dur: 700 },

  { at: G + 1300, to: 'palette-rest', dur: 700 },
]

export const clicks: { at: number; double?: boolean }[] = [
  { at: 1600 },
  { at: 3000 },
  { at: C + 1300 },
  { at: C + 2700, double: true },
  { at: F + 700 },
  { at: F + 1300 },
  { at: F + 2350 },
  { at: F + 3700 },
  { at: E + 700, double: true },
  { at: E + 2400, double: true },
  { at: E + 5000 },
  { at: Q + 600 },
  { at: Q + 10200 },
  { at: A + 600 },
  { at: A + 1600 },
]

export function cursorAt(t: number): { from: string; to: string; p: number } {
  let prev = 'start'
  for (const m of moves) {
    const start = m.at - m.dur
    if (t < start) return { from: prev, to: prev, p: 1 }
    if (t < m.at) return { from: prev, to: m.to, p: ease((t - start) / m.dur) }
    prev = m.to
  }
  return { from: prev, to: prev, p: 1 }
}

/** Shortcuts shown as keycaps over the window while they "happen". */
export const keycaps: { at: number; dur: number; keys: string[]; label: string }[] = [
  { at: E + 3500, dur: 1100, keys: ['⌘', 'S'], label: 'Review changes' },
  { at: Q + 8500, dur: 1100, keys: ['⌘', '↵'], label: 'Run statement' },
  { at: A + 3350, dur: 900, keys: ['↵'], label: 'Send' },
  { at: G + 100, dur: 1100, keys: ['⌘', 'P'], label: 'Go to anything' },
  { at: G + 2000, dur: 800, keys: ['↵'], label: 'Open' },
]

export const toasts: { from: number; to: number; text: string }[] = [
  { from: C + 3100, to: C + 4700, text: 'orders · 500 rows in 12 ms' },
  { from: F + 3900, to: F + 5300, text: 'Filter applied · 5 rows in 3 ms' },
  { from: E + 5150, to: E + 6400, text: '2 rows updated in one transaction' },
  { from: Q + 8700, to: Q + 10000, text: '50 rows in 5 ms' },
]

// ---------------------------------------------------------------------------------------------------------------
// Chapter 1: the Welcome window TableGrip opens with. A recent connection is picked and opened in the main window.

export const WELCOME = {
  in: [0, 350] as const,
  select: 1600,
  connect: 3000,
  /** The Welcome window goes, and the main window comes up in its place. */
  out: [3120, 3450] as const,
  mainIn: [3300, 3800] as const,
}

export type WelcomeRow = {
  id: string
  name: string
  engine: string
  mono: string
  color: string
  env?: string
  target: string
  meta?: string
  readOnly?: boolean
  favorite?: boolean
}

export const welcomeRecent: WelcomeRow[] = [
  { id: 'analytics', name: 'analytics', engine: 'PostgreSQL', mono: 'AN', color: '#d9822b', env: 'STG', target: 'analyst@pg.analytics.internal:5432/warehouse', meta: '2 min ago' },
  { id: 'shop', name: 'shop (local)', engine: 'SQLite', mono: 'SL', color: '#6b7a8f', env: 'LOCAL', target: 'shop.db', meta: '1 h ago' },
  { id: 'prod', name: 'shop-prod', engine: 'PostgreSQL', mono: 'SP', color: '#d64545', env: 'PROD', target: 'readonly@db.shop.internal:5432/shop', meta: 'yesterday', readOnly: true, favorite: true },
]

export const welcomeAll: WelcomeRow[] = [
  { id: 'cache', name: 'sessions-cache', engine: 'Redis', mono: 'SC', color: '#3f9c55', env: 'DEV', target: '127.0.0.1:6379' },
  { id: 'events', name: 'events', engine: 'ClickHouse', mono: 'EV', color: '#c9a227', target: 'reader@ch.events.internal:8123/events', meta: 'Analytics' },
  { id: 'catalog', name: 'catalog', engine: 'MongoDB', mono: 'CA', color: '#3f9c55', env: 'DEV', target: 'svc@mongo.dev:27017/catalog', meta: 'Commerce' },
]

// ---------------------------------------------------------------------------------------------------------------
// Chapter 2: browse

export const connections = [
  { id: 'shop', mono: 'SL', name: 'shop (local)', env: 'LOCAL', engine: 'SQLite', color: '#6b7a8f' },
  { id: 'analytics', mono: 'AN', name: 'analytics', env: 'STG', engine: 'PostgreSQL', color: '#d9822b' },
  { id: 'prod', mono: 'SP', name: 'shop-prod', env: 'PROD', engine: 'read-only', color: '#d64545' },
  { id: 'cache', mono: 'SC', name: 'sessions-cache', env: 'DEV', engine: 'Redis', color: '#3f9c55' },
] as const

export const shopTables = [
  { name: 'customers', rows: '240' },
  { name: 'order_items', rows: '9.4k' },
  { name: 'orders', rows: '3.1k' },
  { name: 'products', rows: '86' },
]

export const CONNECT_CLICK = S.connect + 1300
export const TABLES_SHOWN = S.connect + 1550
export const TABLE_OPEN = S.connect + 2750
export const ROWS_IN = S.connect + 2850

// ---------------------------------------------------------------------------------------------------------------
// Chapters 3 and 4: the orders grid

export type Order = { id: number; customer: number; status: string; total: string; created: string }

const o = (id: number, customer: number, status: string, total: string, created: string): Order => ({
  id,
  customer,
  status,
  total,
  created,
})

export const orders: Order[] = [
  o(259001, 215, 'paid', '468.56', '2026-06-25 18:12:00'),
  o(259002, 220, 'paid', '302.65', '2026-08-12 19:35:00'),
  o(259003, 11, 'pending', '451.24', '2026-02-26 23:20:00'),
  o(259004, 144, 'paid', '324.58', '2026-04-29 00:55:00'),
  o(259005, 161, 'paid', '315.75', '2026-04-23 05:11:00'),
  o(259006, 126, 'refunded', '122.1', '2026-10-06 00:08:00'),
  o(259007, 206, 'refunded', '248.03', '2026-08-28 05:47:00'),
  o(259008, 188, 'paid', '375.93', '2026-07-06 05:23:00'),
  o(259009, 201, 'paid', '24.1', '2026-01-25 04:02:00'),
  o(259010, 90, 'shipped', '365.89', '2026-02-27 19:19:00'),
  o(259011, 236, 'refunded', '109.5', '2026-04-06 16:58:00'),
  o(259012, 230, 'pending', '90.97', '2026-10-13 18:17:00'),
  o(259013, 149, 'paid', '129.95', '2026-07-15 01:01:00'),
  o(259014, 158, 'paid', '150.71', '2026-07-07 21:48:00'),
  o(259015, 75, 'shipped', '304.79', '2026-06-02 01:48:00'),
  o(259016, 134, 'paid', '261.96', '2026-05-31 10:55:00'),
  o(259017, 116, 'paid', '41.8', '2026-02-22 16:20:00'),
  o(259018, 149, 'refunded', '293.68', '2026-07-08 10:42:00'),
  o(259019, 41, 'paid', '438.7', '2026-02-08 04:47:00'),
]

export const filteredOrders: Order[] = [
  orders[6],
  orders[17],
  o(259020, 97, 'refunded', '412.40', '2026-09-14 11:20:00'),
  o(259024, 12, 'refunded', '236.75', '2026-09-30 08:05:00'),
  o(259027, 183, 'refunded', '518.20', '2026-10-21 14:37:00'),
]

export const orderColumns = [
  { name: 'id', type: 'integer' },
  { name: 'customer_id', type: 'integer' },
  { name: 'status', type: 'text' },
  { name: 'total', type: 'numeric' },
  { name: 'created_at', type: 'text' },
]

export const FILTER = {
  addClick: F + 700,
  popover: [F + 750, F + 1300] as const,
  hoverStatus: F + 1100,
  chip1: F + 1300,
  chip1Value: { text: 'refunded', start: F + 1500, ms: 55 },
  chip2: F + 2400,
  chip2Value: { text: '200', start: F + 2650, ms: 70 },
  apply: F + 3700,
  outEnd: F + 3950,
}

export const EDIT = {
  total: { id: 259020, open: E + 700, typeStart: E + 1000, text: '41.24', ms: 60, close: E + 1500, from: '412.40' },
  status: { id: 259024, open: E + 2400, typeStart: E + 2700, text: 'paid', ms: 70, close: E + 3200, from: 'refunded' },
  review: [E + 3650, E + 5050] as const,
  commit: E + 5050,
}

// ---------------------------------------------------------------------------------------------------------------
// Chapter 5: the SQL console. Typing is a script of runs and completion popups.

export type Suggestion = { label: string; hint: string }
type Step =
  | { kind: 'type'; text: string; ms: number }
  | { kind: 'pick'; title: string; items: Suggestion[]; ms: number; insert: string }

const script: Step[] = [
  { kind: 'type', text: '-- Revenue by customer, last 90 days\n', ms: 13 },
  {
    kind: 'type',
    text: 'SELECT c.full_name,\n       c.country,\n       count(o.id) AS orders,\n       round(sum(o.total), 2) AS revenue\nFROM customers c\nJOIN ',
    ms: 17,
  },
  {
    kind: 'pick',
    title: 'Joins from foreign keys',
    items: [
      { label: 'orders o ON o.customer_id = c.id', hint: 'FK' },
      { label: 'customers c2', hint: 'table' },
      { label: 'products p', hint: 'table' },
    ],
    ms: 1100,
    insert: 'orders o ON o.customer_id = c.id',
  },
  { kind: 'type', text: '\nWHERE o.st', ms: 32 },
  {
    kind: 'pick',
    title: 'Columns of orders',
    items: [
      { label: 'status', hint: 'text' },
      { label: 'store_id', hint: 'integer' },
    ],
    ms: 600,
    insert: 'atus',
  },
  { kind: 'type', text: " = '", ms: 45 },
  {
    kind: 'pick',
    title: 'Values sampled from orders.status',
    items: [
      { label: 'paid', hint: '2,114' },
      { label: 'shipped', hint: '612' },
      { label: 'pending', hint: '204' },
      { label: 'refunded', hint: '181' },
    ],
    ms: 1000,
    insert: "paid'",
  },
  { kind: 'type', text: '\nGROUP BY c.id\nORDER BY revenue DESC\nLIMIT 50;', ms: 20 },
]

export const SQL_TYPE_START = Q + 1100

export function editorAt(t: number): { text: string; popup: { title: string; items: Suggestion[] } | null } {
  let at = SQL_TYPE_START
  let text = ''
  for (const step of script) {
    if (step.kind === 'type') {
      const end = at + step.text.length * step.ms
      if (t < end) return { text: text + typed(step.text, t, at, step.ms), popup: null }
      text += step.text
      at = end
    } else {
      const end = at + step.ms
      if (t < end) return { text, popup: { title: step.title, items: step.items } }
      text += step.insert
      at = end
    }
  }
  return { text, popup: null }
}

export const SQL = {
  openConsole: Q + 600,
  run: Q + 8600,
  results: Q + 8750,
  chart: Q + 10200,
}

export const revenue = [
  { name: 'Ava Rossi', country: 'FR', orders: 4, revenue: 1417.74 },
  { name: 'Linh Garcia', country: 'AU', orders: 6, revenue: 1378.79 },
  { name: 'Zoe Smith', country: 'BR', orders: 3, revenue: 1154.91 },
  { name: 'Lucas Tran', country: 'AU', orders: 4, revenue: 1145.58 },
  { name: 'Linh Larsen', country: 'US', orders: 3, revenue: 1098.2 },
  { name: 'Mai Haddad', country: 'AU', orders: 3, revenue: 1032.66 },
  { name: 'Omar Silva', country: 'PT', orders: 5, revenue: 987.12 },
  { name: 'Noah Kim', country: 'KR', orders: 2, revenue: 954.3 },
]

// ---------------------------------------------------------------------------------------------------------------
// Chapter 6: the agent

export const AGENT = {
  open: A + 600,
  prompt: {
    text: 'Which customers got a refund and never ordered again? Keep it as a view for the team.',
    start: A + 1700,
    ms: 19,
  },
  send: A + 3450,
  readStart: A + 3650,
  readDone: A + 4500,
  answer: {
    text: 'A customer is lost when their latest order was refunded: 21 of them. This view changes the schema, so it is yours to run:',
    start: A + 4600,
    ms: 9,
  },
  block: A + 5800,
  codeMsPerLine: 110,
  tooltip: A + 7800,
}

export const agentSql = [
  'CREATE VIEW churned_after_refund AS',
  'SELECT c.id, c.full_name,',
  '       max(o.created_at) AS refunded_at',
  'FROM customers c',
  "JOIN orders o ON o.customer_id = c.id",
  " AND o.status = 'refunded'",
  'WHERE NOT EXISTS (',
  '  SELECT 1 FROM orders later',
  '  WHERE later.customer_id = c.id',
  '    AND later.created_at > o.created_at)',
  'GROUP BY c.id;',
]

// ---------------------------------------------------------------------------------------------------------------
// Chapter 7: go to anything, then the end card

export const GOTO = {
  open: G + 250,
  query: { text: 'rev', start: G + 800, ms: 110 },
  close: G + 2150,
  endCard: G + 2700,
}

export const paletteRecent = [
  { kind: 'table', label: 'orders', hint: 'shop (local)' },
  { kind: 'query', label: 'Revenue by customer', hint: 'saved query' },
  { kind: 'table', label: 'customers', hint: 'shop (local)' },
  { kind: 'action', label: 'Compare schemas…', hint: 'action' },
  { kind: 'conn', label: 'shop-prod', hint: 'PROD · read-only' },
]

export const paletteResults = [
  { kind: 'query', label: 'Revenue by customer', hint: 'saved query' },
  { kind: 'view', label: 'revenue_by_month', hint: 'view · analytics' },
  { kind: 'action', label: 'Review pending changes', hint: '⌘S' },
]
