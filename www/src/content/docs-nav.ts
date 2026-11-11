/**
 * The documentation sidebar: groups in reading order, each listing page slugs (src/content/docs/<slug>.md).
 * A page's title and description come from its own front matter.
 */
export const docsNav: { title: string; items: string[] }[] = [
  {
    title: 'Getting started',
    items: ['overview', 'installation', 'quick-start', 'moving-in', 'uninstall'],
  },
  {
    title: 'Connections',
    items: ['connecting', 'databases', 'drivers', 'organising-connections', 'safety'],
  },
  {
    title: 'Query & edit',
    items: ['sql-editor', 'saved-queries', 'data-grid', 'filtering', 'cell-values', 'data-files', 'redis-and-mongodb'],
  },
  {
    title: 'Schema & operations',
    items: ['table-structure', 'schema-tools', 'activity', 'backup-and-restore'],
  },
  {
    title: 'AI & MCP',
    items: ['ai-agent', 'agent-privacy', 'mcp-server', 'mcp-tools'],
  },
  {
    title: 'Make it yours',
    items: ['navigation', 'keyboard-shortcuts', 'settings', 'themes'],
  },
  {
    title: 'Privacy & support',
    items: ['your-data', 'updates', 'troubleshooting'],
  },
  {
    title: 'For developers',
    items: ['build-from-source', 'architecture', 'driver-catalog', 'contributing', 'releases'],
  },
]
