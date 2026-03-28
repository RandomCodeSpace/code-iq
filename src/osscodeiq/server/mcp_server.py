"""MCP server tools for OSSCodeIQ."""
from __future__ import annotations

import json

from fastmcp import FastMCP

mcp = FastMCP(
    "OSSCodeIQ",
    instructions="Code intelligence graph query tools for exploring a codebase's architecture. "
    "Use these tools to query nodes, edges, find components, trace impact, and generate flow diagrams.",
)

_service = None  # Set during app startup


def set_service(svc) -> None:
    global _service
    _service = svc


def _svc():
    if _service is None:
        raise RuntimeError("Service not initialized")
    return _service


def get_mcp_app():
    """Return the MCP ASGI app for mounting into FastAPI."""
    return mcp.http_app(path="/", transport="streamable-http")


# ── Core tools ───────────────────────────────────────────────────────────────


@mcp.tool()
def get_stats() -> str:
    """Get project graph statistics — node counts, edge counts, backend info."""
    return json.dumps(_svc().get_stats(), indent=2)


@mcp.tool()
def query_nodes(kind: str | None = None, limit: int = 50) -> str:
    """Query nodes in the code graph. Filter by kind (endpoint, entity, guard, class, method, component, module, etc.)."""
    return json.dumps(_svc().list_nodes(kind=kind, limit=limit, offset=0), indent=2)


@mcp.tool()
def query_edges(kind: str | None = None, limit: int = 50) -> str:
    """Query edges in the code graph. Filter by kind (calls, imports, depends_on, queries, protects, etc.)."""
    return json.dumps(_svc().list_edges(kind=kind, limit=limit, offset=0), indent=2)


@mcp.tool()
def get_node_neighbors(node_id: str, direction: str = "both") -> str:
    """Get all nodes connected to a given node. Direction: both, in, out."""
    return json.dumps(
        _svc().get_neighbors(node_id, direction=direction, edge_kinds=None), indent=2
    )


@mcp.tool()
def get_ego_graph(center: str, radius: int = 2) -> str:
    """Get the subgraph within N hops of a center node. Returns all nodes and edges in the neighborhood."""
    return json.dumps(
        _svc().get_ego(center, radius=radius, edge_kinds=None), indent=2
    )


@mcp.tool()
def find_cycles(limit: int = 100) -> str:
    """Find circular dependency cycles in the graph."""
    return json.dumps(_svc().find_cycles(limit=limit), indent=2)


@mcp.tool()
def find_shortest_path(source: str, target: str) -> str:
    """Find the shortest path between two nodes."""
    result = _svc().shortest_path(source, target)
    if result is None:
        return json.dumps({"error": f"No path found between {source} and {target}"}, indent=2)
    return json.dumps(result, indent=2)


@mcp.tool()
def find_consumers(target_id: str) -> str:
    """Find nodes that consume from a target (CONSUMES/LISTENS edges)."""
    return json.dumps(_svc().consumers_of(target_id), indent=2)


@mcp.tool()
def find_producers(target_id: str) -> str:
    """Find nodes that produce to a target (PRODUCES/PUBLISHES edges)."""
    return json.dumps(_svc().producers_of(target_id), indent=2)


@mcp.tool()
def find_callers(target_id: str) -> str:
    """Find nodes that call a target (CALLS edges)."""
    return json.dumps(_svc().callers_of(target_id), indent=2)


@mcp.tool()
def find_dependencies(module_id: str) -> str:
    """Find modules that a given module depends on."""
    return json.dumps(_svc().dependencies_of(module_id), indent=2)


@mcp.tool()
def find_dependents(module_id: str) -> str:
    """Find modules that depend on a given module."""
    return json.dumps(_svc().dependents_of(module_id), indent=2)


@mcp.tool()
def generate_flow(view: str = "overview", format: str = "json") -> str:
    """Generate an architecture flow diagram. Views: overview, ci, deploy, runtime, auth. Formats: json, mermaid."""
    return json.dumps(_svc().generate_flow(view, fmt=format), indent=2)


@mcp.tool()
def analyze_codebase(incremental: bool = True) -> str:
    """Trigger codebase analysis. Scans files, runs detectors, builds the code graph."""
    try:
        result = _svc().run_analysis(incremental)
        return json.dumps(result, indent=2)
    except Exception as exc:
        return json.dumps({"error": str(exc)}, indent=2)


@mcp.tool()
def run_cypher(query: str) -> str:
    """Execute a raw Cypher query (requires KuzuDB backend)."""
    try:
        result = _svc().query_cypher(query, None)
        return json.dumps(result, indent=2)
    except ValueError as exc:
        return json.dumps({"error": str(exc)}, indent=2)


# ── Agentic triage tools ────────────────────────────────────────────────────


@mcp.tool()
def find_component_by_file(file_path: str) -> str:
    """Given a file path (e.g. from a stacktrace), find the component/module it belongs to, its layer, and all connected nodes. Use this to map stack traces to architecture."""
    return json.dumps(_svc().find_component_by_file(file_path), indent=2)


@mcp.tool()
def trace_impact(node_id: str, depth: int = 3) -> str:
    """Trace downstream impact of a node — what depends on it, what breaks if it fails. Returns all transitively affected nodes."""
    return json.dumps(_svc().trace_impact(node_id, depth=depth), indent=2)


@mcp.tool()
def find_related_endpoints(identifier: str) -> str:
    """Given a file, class, or entity name, find all API endpoints that interact with it. Useful for mapping business operations to code."""
    return json.dumps(_svc().find_related_endpoints(identifier), indent=2)


@mcp.tool()
def search_graph(query: str, limit: int = 20) -> str:
    """Free-text search across node labels, IDs, and properties. Find components by name or keyword."""
    return json.dumps(_svc().search_graph(query, limit=limit), indent=2)


@mcp.tool()
def read_file(file_path: str) -> str:
    """Read a source file's content for deep analysis. Path is relative to the codebase root."""
    try:
        return _svc().read_file(file_path)
    except ValueError as exc:
        return f"Error: {exc}"
