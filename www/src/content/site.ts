import type { Scene } from '#/components/site/product-shot'

/** Everything the landing page says, in one place, taken from the README and the user guide. */

export const links = {
  github: 'https://github.com/duongductrong/TableGrip',
  releases: 'https://github.com/duongductrong/TableGrip/releases',
  docs: '/docs/overview',
  guide: '/docs/sql-editor',
  drivers: '/docs/drivers',
  agentDocs: '/docs/ai-agent',
  development: '/docs/build-from-source',
  contributing: '/docs/contributing',
  license: 'https://github.com/duongductrong/TableGrip/blob/master/LICENSE',
  sponsors: 'https://github.com/sponsors/duongductrong',
  kofi: 'https://ko-fi.com/duongductrong',
  paypal: 'https://www.paypal.me/duongductrong',
}

export type FeatureMenuIcon = 'editor' | 'grid' | 'schema' | 'agent' | 'databases'

/** What the Features menu in the header lists: one line each, pointing at its section on the home page. */
export const featureMenu: { label: string; description: string; href: string; icon: FeatureMenuIcon }[] = [
  { label: 'SQL editor', description: 'Completion from your schema, EXPLAIN and quick fixes.', href: '/#editor', icon: 'editor' },
  { label: 'Data grid', description: 'Inline edits submitted in one transaction, typed filters.', href: '/#grid', icon: 'grid' },
  { label: 'Schema tools', description: 'Table designer, schema compare and ER diagrams.', href: '/#schema', icon: 'schema' },
  { label: 'AI agent', description: 'Bring Claude Code, Codex or Gemini CLI. Schema, not rows.', href: '/#agent', icon: 'agent' },
  { label: 'Databases', description: 'PostgreSQL, MySQL, SQL Server, Redis, MongoDB and more.', href: '/#databases', icon: 'databases' },
]

export type NavItem = { label: string; href: string }

export const nav: NavItem[] = [
  { label: 'Docs', href: links.docs },
  { label: 'Changelog', href: '/changelog' },
  { label: 'FAQ', href: '/docs/troubleshooting#faq' },
]

/** Monogram tiles in the colours the app gives each engine. */
export const featuredDatabases = [
  { name: 'PostgreSQL', mono: 'PG', color: '#336791' },
  { name: 'MySQL', mono: 'MY', color: '#00758F' },
  { name: 'SQL Server', mono: 'MS', color: '#CC2927' },
  { name: 'SQLite', mono: 'SL', color: '#4A7FA7' },
  { name: 'MongoDB', mono: 'MG', color: '#3F9C35' },
  { name: 'Redis', mono: 'RD', color: '#D82C20' },
  { name: 'ClickHouse', mono: 'CH', color: '#B59A1C' },
  { name: 'DuckDB', mono: 'DK', color: '#C9A100' },
]

export const databaseFamilies = [
  { family: 'PostgreSQL', engines: 'PostgreSQL · CockroachDB · TimescaleDB · Neon · Supabase', note: 'Server activity with lock trees, pg_dump backups, all-or-nothing DDL.' },
  { family: 'MySQL', engines: 'MySQL · MariaDB · TiDB · PlanetScale', note: 'Server activity, mysqldump backups and restore.' },
  { family: 'SQL Server', engines: 'SQL Server · Azure SQL', note: 'All-or-nothing DDL. Azure SQL comes with encryption on.' },
  { family: 'Analytics', engines: 'ClickHouse · DuckDB', note: 'DuckDB opens a file or :memory:.' },
  { family: 'Embedded', engines: 'SQLite · H2 · HSQLDB · Apache Derby', note: 'Open a file, or an in-memory database.' },
  { family: 'Key-value', engines: 'Redis · Valkey · KeyDB · Dragonfly · etcd', note: 'Standalone, Cluster and Sentinel, with TLS.' },
  { family: 'Document', engines: 'MongoDB · FerretDB', note: 'mongodb:// and mongodb+srv:// URLs.' },
  { family: 'Data files', engines: 'CSV / TSV · Excel .xlsx · JSON / NDJSON', note: 'Opened as tables; commits are written back to the file.' },
  { family: 'Anything else', engines: 'Oracle, Firebird and any JDBC database', note: 'Bring the driver’s jar.' },
]

export type Feature = {
  id: string
  title: string
  body: string
  points: string[]
  scene: Scene
  alt: string
  /** The docs page that covers it. */
  docs: string
}

export const features: Feature[] = [
  {
    id: 'editor',
    title: 'A SQL editor that knows your schema',
    body: 'Completion for tables, columns, aliases and CTEs, whole JOIN … ON clauses proposed from foreign keys, and real values sampled from the column.',
    points: ['Formatter and EXPLAIN', 'Quick fixes for misspelt names', 'Results as grid, JSON or chart'],
    scene: 'editor',
    docs: '/docs/sql-editor',
    alt: 'TableGrip SQL console running a revenue query, with the results grid below',
  },
  {
    id: 'grid',
    title: 'A data grid built for editing',
    body: 'Inline edits stay pending until you submit them in one transaction. Foreign keys are clickable, and a value panel shows JSON as a tree, binary as hex and images as pictures.',
    points: ['Typed filter builder: today-7d, 3 months ago', 'Or a raw WHERE clause with completion', 'Export to CSV, Excel and JSON'],
    scene: 'filter',
    docs: '/docs/data-grid',
    alt: 'TableGrip data grid showing refunded orders over 200, filtered with a condition in the filter bar',
  },
  {
    id: 'schema',
    title: 'Schema tools',
    body: 'A table designer, schema compare with generated migrations, ER diagrams as PNG or Mermaid, and CSV, Excel and JSON import with a test data generator.',
    points: ['Columns, indexes, keys, triggers, DDL', 'Compare two schemas, get the migration', 'ER diagrams from foreign keys'],
    scene: 'structure',
    docs: '/docs/schema-tools',
    alt: 'TableGrip structure view of the orders table listing columns, types and keys',
  },
]

export const highlights = [
  { title: 'Safe by default', body: 'Environment colours, read-only connections, confirmations on Production and a warning before DELETE without WHERE.' },
  { title: 'Operations', body: 'Live server activity with lock trees, backup and restore through pg_dump and mysqldump, and scheduled backups.' },
  { title: 'Files are tables too', body: 'Open CSV, Excel and JSON files as tables. Committed changes are written back to the file.' },
  { title: 'Go to anything', body: '⌘P for tables, tabs, saved queries and actions. Sessions come back after a restart.' },
]

export const importSources = ['TablePlus', 'DBeaver', 'DataGrip', '~/.pgpass', '.env files', 'Connection URLs']

export const platforms = [
  { os: 'macOS', pkg: '.dmg', note: 'Signed and notarized · updates itself' },
  { os: 'Windows', pkg: '.msi', note: 'MSI installer' },
  { os: 'Linux', pkg: '.deb', note: 'Debian package' },
]

/** The CLI agents TableGrip can run (core/agent/cli/CliAgentPreset.kt), plus any HTTP provider. */
export const agentClients = ['Claude Code', 'Codex', 'Gemini CLI', 'Cursor Agent', 'Grok', 'OpenCode', 'Antigravity']

export const agentPoints = [
  {
    title: 'Use the agent you already have',
    body: 'Claude Code, Codex, Gemini CLI and other CLIs on your machine, or any Anthropic or OpenAI-compatible API. They run in their read-only modes.',
  },
  {
    title: 'Schema, not rows',
    body: 'By default the agent sees names, types, DDL, your SQL and errors, never values. Production shares no data unless its own options say so.',
  },
  {
    title: 'Nothing runs behind your back',
    body: 'A read-only query runs only after you agree in the chat. Anything that writes is flagged and left for you to run.',
  },
  {
    title: 'Fix with AI',
    body: 'A failed statement gets a fix shown as a diff, applied only when you choose. @ mentions bring in tables, tabs and saved queries.',
  },
]
