// Shared kind → color mapping used across graph and explorer views
export const KIND_COLORS: Record<string, string> = {
  class: '#8b5cf6',
  interface: '#06b6d4',
  method: '#10b981',
  endpoint: '#f59e0b',
  entity: '#f43f5e',
  module: '#7c3aed',
  function: '#14b8a6',
  database: '#eab308',
  config: '#94a3b8',
  config_key: '#64748b',
  test: '#22c55e',
  guard: '#ef4444',
  middleware: '#f97316',
  service: '#3b82f6',
  controller: '#6366f1',
  repository: '#ec4899',
  component: '#0ea5e9',
  route: '#84cc16',
  topic: '#a855f7',
  queue: '#fb923c',
  schema: '#78716c',
  field: '#a8a29e',
  enum: '#c084fc',
  annotation: '#67e8f9',
  type: '#818cf8',
  script: '#fbbf24',
  file: '#e2e8f0',
  package: '#475569',
  import: '#6b7280',
};

export function getKindColor(kind: string): string {
  return KIND_COLORS[kind.toLowerCase()] ?? '#6366f1';
}

// Edge kind → color mapping
export const EDGE_KIND_COLORS: Record<string, string> = {
  calls: '#6366f1',
  imports: '#94a3b8',
  extends: '#8b5cf6',
  implements: '#06b6d4',
  depends_on: '#f59e0b',
  contains: '#64748b',
  defines: '#10b981',
  produces: '#f43f5e',
  consumes: '#ec4899',
  uses: '#3b82f6',
  annotated_by: '#a855f7',
  maps_to: '#14b8a6',
  default: '#6b7280',
};

export function getEdgeColor(kind: string): string {
  return EDGE_KIND_COLORS[kind.toLowerCase()] ?? EDGE_KIND_COLORS.default;
}
