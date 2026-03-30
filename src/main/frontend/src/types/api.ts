// Matches StatsService.computeStats() output (primary, from H2 cache)
export interface ComputedStatsResponse {
  graph: { nodes: number; edges: number; files: number };
  languages: Record<string, number>;
  frameworks: Record<string, number>;
  infra: {
    databases: Record<string, number>;
    messaging: Record<string, number>;
    cloud: Record<string, number>;
  };
  connections: {
    rest: { total: number; by_method: Record<string, number> };
    grpc: number;
    websocket: number;
    producers: number;
    consumers: number;
  };
  auth: Record<string, number>;
  architecture: Record<string, number>;
}

// Matches QueryService.getStats() output (fallback, from Neo4j)
export interface QueryStatsResponse {
  node_count: number;
  edge_count: number;
  nodes_by_kind: Record<string, number>;
  nodes_by_layer: Record<string, number>;
}

// Union type -- the /api/stats endpoint may return either format
export type StatsResponse = ComputedStatsResponse | QueryStatsResponse;

// Type guard
export function isComputedStats(s: StatsResponse): s is ComputedStatsResponse {
  return 'graph' in s;
}

export interface DetailedStatsResponse {
  architecture?: Record<string, unknown>;
  frameworks?: Record<string, unknown>;
  infrastructure?: Record<string, unknown>;
  auth?: Record<string, unknown>;
  connections?: Record<string, unknown>;
  [key: string]: unknown;
}

export interface CategoryStats {
  [key: string]: unknown;
}

export interface KindEntry {
  kind: string;
  count: number;
}

export interface KindsResponse {
  kinds: KindEntry[];
  total: number;
}

export interface NodeResponse {
  id: string;
  kind: string;
  label: string;
  fqn?: string;
  module?: string;
  file_path?: string;
  line_start?: number;
  line_end?: number;
  layer?: string;
  annotations?: string[];
  properties?: Record<string, unknown>;
}

export interface NodesListResponse {
  kind?: string;
  total?: number;
  offset: number;
  limit: number;
  nodes: NodeResponse[];
  count?: number;
}

export interface EdgeResponse {
  id: string;
  kind: string;
  source: string;
  target?: string;
}

export interface EdgesListResponse {
  edges: EdgeResponse[];
  count: number;
  total: number;
}

export interface TopologyResponse {
  services: TopologyNode[];
  connections: TopologyEdge[];
  summary: Record<string, unknown>;
}

export interface TopologyNode {
  id: string;
  name: string;
  type: string;
  kind?: string;
  nodeCount?: number;
  layer?: string;
  properties?: Record<string, unknown>;
}

export interface TopologyEdge {
  source: string;
  target: string;
  type: string;
  label?: string;
  weight?: number;
}

export interface FlowDiagram {
  title: string;
  nodes: FlowNode[];
  edges: FlowEdge[];
}

export interface FlowNode {
  id: string;
  label: string;
  type: string;
  group?: string;
  properties?: Record<string, unknown>;
}

export interface FlowEdge {
  source: string;
  target: string;
  label?: string;
  type?: string;
}

export interface AnalyzeResponse {
  status: string;
  total_files: number;
  files_analyzed: number;
  node_count: number;
  edge_count: number;
  elapsed_ms: number;
}

export interface SearchResult {
  id: string;
  kind: string;
  label: string;
  score?: number;
  file_path?: string;
}
