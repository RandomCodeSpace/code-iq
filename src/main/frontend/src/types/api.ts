export interface StatsResponse {
  total_nodes: number;
  total_edges: number;
  total_files: number;
  languages: Record<string, number>;
  node_kinds: Record<string, number>;
  edge_kinds: Record<string, number>;
  layers: Record<string, number>;
  frameworks?: string[];
  infrastructure?: Record<string, number>;
  auth?: Record<string, number>;
  connections?: Record<string, number>;
  [key: string]: unknown;
}

export interface DetailedStatsResponse {
  architecture?: CategoryStats;
  frameworks?: CategoryStats;
  infrastructure?: CategoryStats;
  auth?: CategoryStats;
  connections?: CategoryStats;
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
