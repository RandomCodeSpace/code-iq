import type {
  StatsResponse,
  KindsResponse,
  NodesListResponse,
  NodeResponse,
  EdgesListResponse,
  SearchResult,
  FileTreeResponse,
  TopologyResponse,
  EgoGraphResponse,
  NeighborsResponse,
} from '@/types/api';

const BASE = '/api';

async function fetchJson<T>(url: string): Promise<T> {
  const res = await fetch(url);
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`API error ${res.status}: ${text}`);
  }
  return res.json();
}


export const api = {
  getStats: () => fetchJson<StatsResponse>(`${BASE}/stats`),

  getDetailedStats: (category = 'all') =>
    fetchJson<Record<string, unknown>>(`${BASE}/stats/detailed?category=${category}`),

  getKinds: () => fetchJson<KindsResponse>(`${BASE}/kinds`),

  getNodesByKind: (kind: string, limit = 50, offset = 0) =>
    fetchJson<NodesListResponse>(`${BASE}/kinds/${kind}?limit=${limit}&offset=${offset}`),

  getNodes: (kind?: string, module?: string, limit = 100, offset = 0) => {
    const params = new URLSearchParams({ limit: String(limit), offset: String(offset) });
    if (kind) params.set('kind', kind);
    if (module) params.set('module', module);
    return fetchJson<NodesListResponse>(`${BASE}/nodes?${params}`);
  },

  findNode: (q: string) =>
    fetchJson<SearchResult[]>(`${BASE}/nodes/find?q=${encodeURIComponent(q)}`),

  getNodeDetail: (id: string) =>
    fetchJson<NodeResponse>(`${BASE}/nodes/${encodeURIComponent(id)}/detail`),

  getNodeNeighbors: (id: string, direction = 'both') =>
    fetchJson<Record<string, unknown>>(`${BASE}/nodes/${encodeURIComponent(id)}/neighbors?direction=${direction}`),

  getEdges: (kind?: string, limit = 100, offset = 0) => {
    const params = new URLSearchParams({ limit: String(limit), offset: String(offset) });
    if (kind) params.set('kind', kind);
    return fetchJson<EdgesListResponse>(`${BASE}/edges?${params}`);
  },

  search: (q: string, limit = 50) =>
    fetchJson<SearchResult[]>(`${BASE}/search?q=${encodeURIComponent(q)}&limit=${limit}`),

  readFile: async (path: string, startLine?: number, endLine?: number) => {
    const params = new URLSearchParams({ path });
    if (startLine !== undefined) params.set('startLine', String(startLine));
    if (endLine !== undefined) params.set('endLine', String(endLine));
    const r = await fetch(`${BASE}/file?${params}`);
    if (!r.ok) throw new Error(`API error ${r.status}`);
    return r.text();
  },

  getCycles: (limit = 100) =>
    fetchJson<Record<string, unknown>>(`${BASE}/query/cycles?limit=${limit}`),

  getShortestPath: (source: string, target: string) =>
    fetchJson<Record<string, unknown>>(`${BASE}/query/shortest-path?source=${encodeURIComponent(source)}&target=${encodeURIComponent(target)}`),

  getConsumers: (id: string) =>
    fetchJson<Record<string, unknown>>(`${BASE}/query/consumers/${encodeURIComponent(id)}`),

  getProducers: (id: string) =>
    fetchJson<Record<string, unknown>>(`${BASE}/query/producers/${encodeURIComponent(id)}`),

  getCallers: (id: string) =>
    fetchJson<Record<string, unknown>>(`${BASE}/query/callers/${encodeURIComponent(id)}`),

  getDependencies: (id: string) =>
    fetchJson<Record<string, unknown>>(`${BASE}/query/dependencies/${encodeURIComponent(id)}`),

  getDependents: (id: string) =>
    fetchJson<Record<string, unknown>>(`${BASE}/query/dependents/${encodeURIComponent(id)}`),

  findComponent: (file: string) =>
    fetchJson<Record<string, unknown>>(`${BASE}/triage/component?file=${encodeURIComponent(file)}`),

  traceImpact: (id: string, depth = 3) =>
    fetchJson<Record<string, unknown>>(`${BASE}/triage/impact/${encodeURIComponent(id)}?depth=${depth}`),

  getFileTree: (depth?: number, path?: string) => {
    const qs = new URLSearchParams();
    if (depth !== undefined) qs.set('depth', String(depth));
    if (path !== undefined && path !== '') qs.set('path', path);
    const suffix = qs.toString() ? `?${qs}` : '';
    return fetchJson<FileTreeResponse>(`${BASE}/file-tree${suffix}`);
  },

  getTopology: () =>
    fetchJson<TopologyResponse>(`${BASE}/topology`),

  getEgoGraph: (center: string, radius = 2) =>
    fetchJson<EgoGraphResponse>(`${BASE}/ego/${encodeURIComponent(center)}?radius=${radius}`),

  getNodeNeighborsTyped: (id: string) =>
    fetchJson<NeighborsResponse>(`${BASE}/nodes/${encodeURIComponent(id)}/neighbors`),
};
