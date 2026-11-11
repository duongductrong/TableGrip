import type { Scene } from '#/components/site/product-shot'

/** Everything the landing page says, in one place, taken from the README and the user guide. */

export const links = {
  github: 'https://github.com/duongductrong/TableGrip',
  releases: 'https://github.com/duongductrong/TableGrip/releases',
  docs: '/docs/overview',
  guide: '/docs/sql-editor',
  drivers: '/docs/drivers',
  agentDocs: '/docs/ai-agent',
  mcpDocs: '/docs/mcp-server',
  development: '/docs/build-from-source',
  contributing: '/docs/contributing',
  license: 'https://github.com/duongductrong/TableGrip/blob/master/LICENSE',
  sponsors: 'https://github.com/sponsors/duongductrong',
  kofi: 'https://ko-fi.com/duongductrong',
  paypal: 'https://www.paypal.me/duongductrong',
}

/**
 * Every engine the app's connection dialog offers (core/.../connection/DatabaseType.kt), grouped by family. Keep the
 * two in step: the counts on the site are worked out from this list.
 */
export const databaseFamilies: { family: string; engines: string[]; kind: 'database' | 'file'; note: string }[] = [
  { family: 'PostgreSQL', kind: 'database', engines: ['PostgreSQL', 'CockroachDB', 'TimescaleDB', 'YugabyteDB', 'Neon', 'Supabase', 'Amazon Redshift'], note: 'Server activity with lock trees, pg_dump backups, all-or-nothing DDL.' },
  { family: 'MySQL', kind: 'database', engines: ['MySQL', 'MariaDB', 'TiDB', 'PlanetScale', 'OceanBase'], note: 'Server activity, mysqldump backups and restore.' },
  { family: 'SQL Server & Oracle', kind: 'database', engines: ['SQL Server', 'Azure SQL', 'Oracle', 'Firebird'], note: 'All-or-nothing DDL on SQL Server. Azure SQL comes with encryption on.' },
  { family: 'Analytics', kind: 'database', engines: ['ClickHouse', 'DuckDB', 'Trino', 'Presto', 'Apache Doris', 'StarRocks', 'QuestDB'], note: 'DuckDB opens a file or :memory:. Trino and Presto query across catalogs.' },
  { family: 'Cloud warehouses', kind: 'database', engines: ['Snowflake', 'Databricks', 'Google BigQuery', 'Cloud Spanner'], note: 'Spanner edits rows in transactions; bring the Snowflake, Databricks and BigQuery jars.' },
  { family: 'Enterprise', kind: 'database', engines: ['IBM Db2', 'SAP HANA', 'Vertica', 'Exasol', 'IBM Informix', 'InterSystems IRIS'], note: 'Row editing and transactions, with the vendor’s own JDBC jar.' },
  { family: 'Hadoop & Spark', kind: 'database', engines: ['Apache Hive', 'Spark SQL', 'Apache Impala', 'Apache Kyuubi'], note: 'Query-only, through Kyuubi’s Hive JDBC driver.' },
  { family: 'Embedded', kind: 'database', engines: ['SQLite', 'H2', 'HSQLDB', 'Apache Derby', 'Microsoft Access'], note: 'Open a file, or an in-memory database. Access files open without Access.' },
  { family: 'Key-value', kind: 'database', engines: ['Redis', 'Valkey', 'KeyDB', 'Dragonfly', 'etcd', 'Consul', 'Apache ZooKeeper'], note: 'Standalone, Cluster and Sentinel, with TLS.' },
  { family: 'Document', kind: 'database', engines: ['MongoDB', 'FerretDB', 'Apache CouchDB', 'Amazon DynamoDB'], note: 'mongodb:// URLs; Mango queries for CouchDB, PartiQL for DynamoDB.' },
  { family: 'Search', kind: 'database', engines: ['Elasticsearch', 'OpenSearch', 'Meilisearch', 'Apache Solr'], note: 'Indices and mappings, query DSL, a Dev Tools console.' },
  { family: 'Wide-column', kind: 'database', engines: ['Apache Cassandra', 'ScyllaDB'], note: 'Keyspaces and tables, paged rows, a CQL console.' },
  { family: 'Time series', kind: 'database', engines: ['InfluxDB 2', 'InfluxDB 3', 'VictoriaMetrics', 'Prometheus'], note: 'Flux, SQL and PromQL, read-only.' },
  { family: 'Vector', kind: 'database', engines: ['Qdrant'], note: 'Collections, points by filter, payload editing.' },
  { family: 'Messaging', kind: 'database', engines: ['Apache Kafka', 'RabbitMQ'], note: 'Topics by partition and offset, queues peeked; publish messages.' },
  { family: 'Graph', kind: 'database', engines: ['Neo4j'], note: 'Labels and relationships, a Cypher console.' },
  { family: 'Data files', kind: 'file', engines: ['CSV / TSV', 'Excel .xlsx', 'JSON / NDJSON'], note: 'Opened as tables; commits are written back to the file.' },
  { family: 'Anything else', kind: 'database', engines: ['Any JDBC database'], note: 'Bring the driver’s jar.' },
]

const countEngines = (kind: 'database' | 'file') =>
  databaseFamilies.filter((f) => f.kind === kind && f.family !== 'Anything else').reduce((n, f) => n + f.engines.length, 0)

/** How many databases and data-file formats TableGrip connects to out of the box, not counting “any JDBC driver”. */
export const supportedCounts = {
  databases: countEngines('database'),
  files: countEngines('file'),
  families: databaseFamilies.filter((f) => f.kind === 'database' && f.family !== 'Anything else').length,
}

export type FeatureMenuIcon = 'editor' | 'grid' | 'schema' | 'agent' | 'mcp' | 'databases'

/** What the Features menu in the header lists: one line each, pointing at its section on the home page. */
export const featureMenu: { label: string; description: string; href: string; icon: FeatureMenuIcon }[] = [
  { label: 'SQL editor', description: 'Completion from your schema, EXPLAIN and quick fixes.', href: '/#editor', icon: 'editor' },
  { label: 'Data grid', description: 'Inline edits submitted in one transaction, typed filters.', href: '/#grid', icon: 'grid' },
  { label: 'Schema tools', description: 'Table designer, schema compare and ER diagrams.', href: '/#schema', icon: 'schema' },
  { label: 'AI agent', description: 'Bring Claude Code, Codex or Gemini CLI. Schema, not rows.', href: '/#agent', icon: 'agent' },
  { label: 'MCP server', description: 'Your saved connections in Claude, Cursor and VS Code.', href: '/#mcp', icon: 'mcp' },
  { label: 'Databases', description: `${supportedCounts.databases} databases and ${supportedCounts.files} file formats, from PostgreSQL to Redis.`, href: '/#databases', icon: 'databases' },
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

export const importSources = ['DBeaver', 'IDE data sources', 'Connections.plist', '~/.pgpass', '.env files', 'Connection URLs']

export const platforms = [
  { id: 'macos', os: 'macOS', pkg: '.dmg', note: 'Signed and notarized · updates itself' },
  { id: 'windows', os: 'Windows', pkg: '.msi', note: 'MSI installer' },
  { id: 'linux', os: 'Linux', pkg: '.deb', note: 'Debian package' },
] as const

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

/** AI apps Settings › MCP can add TableGrip to (mcp/McpSetup.kt), plus Claude Code by command. */
export const mcpClients = ['Claude Desktop', 'Claude Code', 'Cursor', 'VS Code', 'Windsurf']

/** The command a terminal (and Claude Code) uses to start the server. */
export const mcpCommand = 'claude mcp add --scope user tablegrip -- tablegrip mcp'

export const mcpSteps = [
  { title: 'Turn it on', body: 'Settings › MCP. Off until you do.' },
  { title: 'Install the command', body: 'One click puts tablegrip on your PATH.' },
  { title: 'Add it to your app', body: 'TableGrip writes the config, or copy the command.' },
]

export const mcpPoints = [
  {
    title: 'The same rules as the app',
    body: 'Each connection decides: hidden, schema only, or schema and data. Production never shares data unless its own options say so.',
  },
  {
    title: 'Read-only unless you say so',
    body: 'Writes need a per-connection opt-in. Read-only connections and data files are never written to, and production asks first.',
  },
  {
    title: 'Every statement is checked',
    body: 'One statement per call, each one classified. Reads run in a transaction that is always rolled back. Results are capped.',
  },
  {
    title: 'No secrets in any config',
    body: 'The app config holds only "tablegrip mcp". Passwords stay in the system keychain, and every call is logged.',
  },
]
