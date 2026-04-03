import {
  BarChart3, Search, GitBranch, Layers, Zap, Shield, Code2, FileText,
} from "lucide-react";

export interface ToolParam {
  name: string;
  type: "string" | "number" | "boolean";
  description: string;
  required?: boolean;
  default?: string;
  options?: string[];
}

export interface McpTool {
  name: string;
  description: string;
  category: string;
  icon: typeof BarChart3;
  params: ToolParam[];
  url: string | ((params: Record<string, string>) => string);
  method?: "GET" | "POST";
}

export const CATEGORIES = [
  { id: "stats",    label: "Statistics",       icon: BarChart3, color: "emerald" },
  { id: "query",    label: "Graph Queries",     icon: Search,    color: "blue"    },
  { id: "topology", label: "Service Topology",  icon: GitBranch, color: "purple"  },
  { id: "flow",     label: "Architecture Flow", icon: Layers,    color: "amber"   },
  { id: "analysis", label: "Analysis",          icon: Zap,       color: "rose"    },
  { id: "security", label: "Security",          icon: Shield,    color: "red"     },
  { id: "code",     label: "Code",              icon: Code2,     color: "cyan"    },
] as const;

export type CategoryId = (typeof CATEGORIES)[number]["id"];

export const COLOR_MAP: Record<string, string> = {
  emerald: "bg-emerald-500/10 text-emerald-400 border-emerald-500/20",
  blue:    "bg-blue-500/10 text-blue-400 border-blue-500/20",
  purple:  "bg-purple-500/10 text-purple-400 border-purple-500/20",
  amber:   "bg-amber-500/10 text-amber-400 border-amber-500/20",
  rose:    "bg-rose-500/10 text-rose-400 border-rose-500/20",
  red:     "bg-red-500/10 text-red-400 border-red-500/20",
  cyan:    "bg-cyan-500/10 text-cyan-400 border-cyan-500/20",
};

export const TOOLS: McpTool[] = [
  // ── Statistics ────────────────────────────────────────────────────────────────
  {
    name: "get_stats",
    description: "Graph statistics — node counts, edge counts, breakdown by kind and layer",
    category: "stats", icon: BarChart3, params: [], url: "/api/stats",
  },
  {
    name: "get_detailed_stats",
    description: "Rich categorized statistics: frameworks, infra, connections, auth, architecture",
    category: "stats", icon: BarChart3,
    params: [
      { name: "category", type: "string", description: "Category filter",
        options: ["all", "graph", "languages", "frameworks", "infra", "connections", "auth", "architecture"],
        default: "all" },
    ],
    url: (p) => `/api/stats/detailed?category=${p.category || "all"}`,
  },

  // ── Graph Queries ─────────────────────────────────────────────────────────────
  {
    name: "query_nodes",
    description: "Query nodes with optional kind filter",
    category: "query", icon: Search,
    params: [
      { name: "kind", type: "string", description: "Node kind filter",
        options: ["", "endpoint", "entity", "class", "method", "module", "guard", "config_key", "infra_resource", "component", "service"] },
      { name: "limit", type: "number", description: "Max results", default: "20" },
    ],
    url: (p) => `/api/nodes?kind=${p.kind || ""}&limit=${p.limit || "20"}`,
  },
  {
    name: "query_edges",
    description: "Query edges with optional kind filter",
    category: "query", icon: Search,
    params: [
      { name: "kind", type: "string", description: "Edge kind filter",
        options: ["", "calls", "depends_on", "imports", "extends", "implements", "exposes", "produces", "consumes", "protects"] },
      { name: "limit", type: "number", description: "Max results", default: "20" },
    ],
    url: (p) => `/api/edges?kind=${p.kind || ""}&limit=${p.limit || "20"}`,
  },
  {
    name: "list_kinds",
    description: "List all node kinds with counts",
    category: "query", icon: Search, params: [], url: "/api/kinds",
  },
  {
    name: "get_node_detail",
    description: "Full detail for a specific node including properties and edges",
    category: "query", icon: Search,
    params: [{ name: "nodeId", type: "string", description: "Node ID", required: true }],
    url: (p) => `/api/nodes/${encodeURIComponent(p.nodeId || "")}/detail`,
  },
  {
    name: "get_neighbors",
    description: "Get neighbor nodes with optional direction filter",
    category: "query", icon: Search,
    params: [
      { name: "nodeId", type: "string", description: "Node ID", required: true },
      { name: "direction", type: "string", description: "Direction",
        options: ["both", "in", "out"], default: "both" },
    ],
    url: (p) => `/api/nodes/${encodeURIComponent(p.nodeId || "")}/neighbors?direction=${p.direction || "both"}`,
  },
  {
    name: "get_ego_graph",
    description: "Ego subgraph — all nodes within N hops of a center node",
    category: "query", icon: Search,
    params: [
      { name: "center", type: "string", description: "Center node ID", required: true },
      { name: "radius", type: "number", description: "Hop radius (1–10)", default: "2" },
    ],
    url: (p) => `/api/ego/${encodeURIComponent(p.center || "")}?radius=${p.radius || "2"}`,
  },
  {
    name: "search_graph",
    description: "Free-text search across node labels, IDs, and properties",
    category: "query", icon: Search,
    params: [
      { name: "q", type: "string", description: "Search query", required: true },
      { name: "limit", type: "number", description: "Max results", default: "20" },
    ],
    url: (p) => `/api/search?q=${encodeURIComponent(p.q || "")}&limit=${p.limit || "20"}`,
  },
  {
    name: "find_cycles",
    description: "Find circular dependency cycles in the graph",
    category: "query", icon: Search,
    params: [{ name: "limit", type: "number", description: "Max cycles", default: "10" }],
    url: (p) => `/api/query/cycles?limit=${p.limit || "10"}`,
  },
  {
    name: "find_shortest_path",
    description: "Shortest path between two nodes",
    category: "query", icon: Search,
    params: [
      { name: "source", type: "string", description: "Source node ID", required: true },
      { name: "target", type: "string", description: "Target node ID", required: true },
    ],
    url: (p) => `/api/query/shortest-path?source=${encodeURIComponent(p.source || "")}&target=${encodeURIComponent(p.target || "")}`,
  },
  {
    name: "find_callers",
    description: "Find all nodes that call a target node",
    category: "query", icon: Search,
    params: [{ name: "id", type: "string", description: "Target node ID", required: true }],
    url: (p) => `/api/query/callers/${encodeURIComponent(p.id || "")}`,
  },
  {
    name: "find_dependencies",
    description: "Find all nodes this node depends on (outgoing dependencies)",
    category: "query", icon: Search,
    params: [{ name: "id", type: "string", description: "Node ID", required: true }],
    url: (p) => `/api/query/dependencies/${encodeURIComponent(p.id || "")}`,
  },
  {
    name: "find_dead_code",
    description: "Find potentially dead code — classes/methods with no incoming references",
    category: "query", icon: Search,
    params: [
      { name: "kind", type: "string", description: "Filter by kind",
        options: ["", "class", "method", "interface"] },
      { name: "limit", type: "number", description: "Max results", default: "20" },
    ],
    url: (p) => `/api/query/dead-code?kind=${p.kind || ""}&limit=${p.limit || "20"}`,
  },

  // ── Service Topology ──────────────────────────────────────────────────────────
  {
    name: "get_topology",
    description: "Full service topology map — services and their connections",
    category: "topology", icon: GitBranch, params: [], url: "/api/topology",
  },
  {
    name: "service_detail",
    description: "Detailed view of a specific service",
    category: "topology", icon: GitBranch,
    params: [{ name: "name", type: "string", description: "Service name", required: true }],
    url: (p) => `/api/topology/services/${encodeURIComponent(p.name || "")}`,
  },
  {
    name: "service_dependencies",
    description: "What a service depends on (DBs, queues, other services)",
    category: "topology", icon: GitBranch,
    params: [{ name: "name", type: "string", description: "Service name", required: true }],
    url: (p) => `/api/topology/services/${encodeURIComponent(p.name || "")}/deps`,
  },
  {
    name: "service_dependents",
    description: "Services that depend on this service",
    category: "topology", icon: GitBranch,
    params: [{ name: "name", type: "string", description: "Service name", required: true }],
    url: (p) => `/api/topology/services/${encodeURIComponent(p.name || "")}/dependents`,
  },
  {
    name: "blast_radius",
    description: "BFS blast radius — all services affected if this node fails",
    category: "topology", icon: GitBranch,
    params: [{ name: "nodeId", type: "string", description: "Node ID", required: true }],
    url: (p) => `/api/topology/blast-radius/${encodeURIComponent(p.nodeId || "")}`,
  },
  {
    name: "find_path",
    description: "Find shortest path between two services",
    category: "topology", icon: GitBranch,
    params: [
      { name: "from", type: "string", description: "Source service name", required: true },
      { name: "to", type: "string", description: "Target service name", required: true },
    ],
    url: (p) => `/api/topology/path?from=${encodeURIComponent(p.from || "")}&to=${encodeURIComponent(p.to || "")}`,
  },
  {
    name: "find_bottlenecks",
    description: "Services with the most connections (potential bottlenecks)",
    category: "topology", icon: GitBranch, params: [], url: "/api/topology/bottlenecks",
  },
  {
    name: "find_circular_deps",
    description: "Circular service-to-service dependency cycles",
    category: "topology", icon: GitBranch, params: [], url: "/api/topology/circular",
  },
  {
    name: "find_dead_services",
    description: "Services with no incoming connections (potentially unused)",
    category: "topology", icon: GitBranch, params: [], url: "/api/topology/dead",
  },

  // ── Architecture Flow ─────────────────────────────────────────────────────────
  {
    name: "generate_flow",
    description: "Generate architecture flow diagram for a specific view type",
    category: "flow", icon: Layers,
    params: [
      { name: "view", type: "string", description: "View type",
        options: ["overview", "ci", "deploy", "runtime", "auth"], default: "overview" },
    ],
    url: (p) => `/api/flow/${p.view || "overview"}?format=json`,
  },
  {
    name: "list_flows",
    description: "List available architecture flow view types",
    category: "flow", icon: Layers, params: [], url: "/api/flow",
  },

  // ── Analysis ──────────────────────────────────────────────────────────────────
  {
    name: "find_component_by_file",
    description: "Find which component/layer a file belongs to",
    category: "analysis", icon: Zap,
    params: [{ name: "file", type: "string", description: "File path (relative to codebase root)", required: true }],
    url: (p) => `/api/triage/component?file=${encodeURIComponent(p.file || "")}`,
  },
  {
    name: "trace_impact",
    description: "Trace downstream impact from a node",
    category: "analysis", icon: Zap,
    params: [
      { name: "nodeId", type: "string", description: "Node ID", required: true },
      { name: "depth", type: "number", description: "Max depth", default: "3" },
    ],
    url: (p) => `/api/triage/impact/${encodeURIComponent(p.nodeId || "")}?depth=${p.depth || "3"}`,
  },
  {
    name: "find_consumers",
    description: "Find nodes that consume/listen to a target",
    category: "analysis", icon: Zap,
    params: [{ name: "targetId", type: "string", description: "Target node ID", required: true }],
    url: (p) => `/api/query/consumers/${encodeURIComponent(p.targetId || "")}`,
  },
  {
    name: "find_producers",
    description: "Find nodes that produce/publish to a target",
    category: "analysis", icon: Zap,
    params: [{ name: "targetId", type: "string", description: "Target node ID", required: true }],
    url: (p) => `/api/query/producers/${encodeURIComponent(p.targetId || "")}`,
  },
  {
    name: "find_dependents",
    description: "Find all nodes that depend on this node (incoming dependencies)",
    category: "analysis", icon: Zap,
    params: [{ name: "id", type: "string", description: "Node ID", required: true }],
    url: (p) => `/api/query/dependents/${encodeURIComponent(p.id || "")}`,
  },
  {
    name: "find_node",
    description: "Find a node by name or qualified name",
    category: "analysis", icon: Zap,
    params: [
      { name: "q", type: "string", description: "Node name or qualified name", required: true },
      { name: "kind", type: "string", description: "Optional kind filter",
        options: ["", "class", "method", "endpoint", "entity", "component", "service"] },
    ],
    url: (p) => `/api/search?q=${encodeURIComponent(p.q || "")}&kind=${p.kind || ""}&limit=10`,
  },
  {
    name: "find_related_endpoints",
    description: "Find REST endpoints related to or calling a node",
    category: "analysis", icon: Zap,
    params: [{ name: "nodeId", type: "string", description: "Node ID", required: true }],
    url: (p) => `/api/query/callers/${encodeURIComponent(p.nodeId || "")}?kind=endpoint`,
  },
  {
    name: "run_cypher",
    description: "Execute a raw Cypher query against the Neo4j graph (MCP protocol only)",
    category: "analysis", icon: Zap,
    params: [{ name: "query", type: "string", description: "Cypher query string", required: true }],
    url: "/api/cypher",
    method: "POST",
  },

  // ── Security ──────────────────────────────────────────────────────────────────
  {
    name: "find_unprotected",
    description: "Find endpoints without auth guards",
    category: "security", icon: Shield, params: [], url: "/api/flow/auth?format=json",
  },
  // ── Code ──────────────────────────────────────────────────────────────────────
  {
    name: "read_file",
    description: "Read source file content with optional line range",
    category: "code", icon: FileText,
    params: [
      { name: "path", type: "string", description: "File path (relative to codebase root)", required: true },
      { name: "startLine", type: "number", description: "Start line number" },
      { name: "endLine", type: "number", description: "End line number" },
    ],
    url: (p) =>
      `/api/file?path=${encodeURIComponent(p.path || "")}${p.startLine ? `&startLine=${p.startLine}` : ""}${p.endLine ? `&endLine=${p.endLine}` : ""}`,
  },
];

/** Group tools by category ID. */
export function toolsByCategory(): Record<string, McpTool[]> {
  const result: Record<string, McpTool[]> = {};
  for (const cat of CATEGORIES) result[cat.id] = [];
  for (const tool of TOOLS) {
    if (!result[tool.category]) result[tool.category] = [];
    result[tool.category].push(tool);
  }
  return result;
}
