"""Shared service layer for OSSCodeIQ server.

Both REST routes and MCP tools call these methods.
Every public method returns plain dicts/lists (JSON-serializable).
"""
from __future__ import annotations

import logging
import threading
from collections import deque
from pathlib import Path
from typing import Any

from osscodeiq.analyzer import Analyzer, AnalysisResult
from osscodeiq.cache.store import CacheStore
from osscodeiq.config import Config
from osscodeiq.flow.engine import FlowEngine
from osscodeiq.graph.backends import create_backend
from osscodeiq.graph.query import GraphQuery
from osscodeiq.graph.store import GraphStore
from osscodeiq.models.graph import (
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
    SourceLocation,
)

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Serialization helpers
# ---------------------------------------------------------------------------


def _node_to_dict(node: GraphNode) -> dict:
    """Convert a GraphNode to a JSON-serializable dict."""
    return {
        "id": node.id,
        "kind": node.kind.value,
        "label": node.label,
        "fqn": node.fqn,
        "module": node.module,
        "location": (
            {
                "file_path": node.location.file_path,
                "line_start": node.location.line_start,
                "line_end": node.location.line_end,
            }
            if node.location
            else None
        ),
        "annotations": node.annotations,
        "properties": node.properties,
    }


def _edge_to_dict(edge: GraphEdge) -> dict:
    """Convert a GraphEdge to a JSON-serializable dict."""
    return {
        "source": edge.source,
        "target": edge.target,
        "kind": edge.kind.value,
        "label": edge.label,
        "properties": edge.properties,
    }


def _store_to_dict(store: GraphStore) -> dict:
    """Convert a GraphStore to a JSON-serializable dict with sorted output."""
    return {
        "nodes": [
            _node_to_dict(n)
            for n in sorted(store.all_nodes(), key=lambda n: n.id)
        ],
        "edges": [
            _edge_to_dict(e)
            for e in sorted(store.all_edges(), key=lambda e: (e.source, e.target))
        ],
    }


# ---------------------------------------------------------------------------
# Service
# ---------------------------------------------------------------------------


class CodeIQService:
    """Stateful service wrapping the code-intelligence library.

    Thread-safe: analysis replaces the internal store under a lock;
    read operations are safe against a snapshot reference.
    """

    def __init__(
        self,
        path: Path,
        backend: str = "networkx",
        config_path: Path | None = None,
    ) -> None:
        self._path = path.resolve()
        self._backend_name = backend
        self._config = self._load_config(config_path)
        self._store: GraphStore | None = None
        self._lock = threading.Lock()

    # -- internal helpers ------------------------------------------------

    @staticmethod
    def _load_config(config_path: Path | None) -> Config:
        if config_path and config_path.exists():
            return Config.load(config_path=config_path)
        return Config()

    @property
    def store(self) -> GraphStore:
        """Lazy-load and return the graph store."""
        if self._store is None:
            self._store = self._open_store()
        return self._store

    def _open_store(self) -> GraphStore:
        """Open or create a GraphStore for the configured backend."""
        graph_dir = self._path / ".code-intelligence"
        if self._backend_name == "kuzu":
            db_path = str(graph_dir / "graph.kuzu")
            backend_obj = create_backend("kuzu", path=db_path)
            return GraphStore(backend=backend_obj)
        elif self._backend_name == "sqlite":
            db_path = str(graph_dir / "graph.db")
            backend_obj = create_backend("sqlite", path=db_path)
            return GraphStore(backend=backend_obj)
        else:
            # NetworkX — load from cache
            cache_path = (
                self._path
                / self._config.cache.directory
                / self._config.cache.db_name
            )
            if not cache_path.exists():
                return GraphStore()  # empty graph for server
            cache = CacheStore(cache_path)
            return cache.load_full_graph()

    # -- public API (all return dicts/lists) ----------------------------

    def get_stats(self) -> dict:
        """Return high-level graph statistics."""
        model = self.store.to_model()
        stats: dict[str, Any] = dict(model.metadata.get("stats", {}))
        stats["backend"] = self._backend_name
        stats["codebase_path"] = str(self._path)
        return stats

    def list_nodes(
        self,
        kind: str | None = None,
        limit: int = 100,
        offset: int = 0,
    ) -> list[dict]:
        """List nodes, optionally filtered by kind, with pagination."""
        if kind is not None:
            nodes = self.store.nodes_by_kind(NodeKind(kind))
        else:
            nodes = self.store.all_nodes()
        nodes = sorted(nodes, key=lambda n: n.id)
        return [_node_to_dict(n) for n in nodes[offset : offset + limit]]

    def list_edges(
        self,
        kind: str | None = None,
        limit: int = 100,
        offset: int = 0,
    ) -> list[dict]:
        """List edges, optionally filtered by kind, with pagination."""
        if kind is not None:
            edges = self.store.edges_by_kind(EdgeKind(kind))
        else:
            edges = self.store.all_edges()
        edges = sorted(edges, key=lambda e: (e.source, e.target))
        return [_edge_to_dict(e) for e in edges[offset : offset + limit]]

    def get_node(self, node_id: str) -> dict | None:
        """Return a single node by id, or None."""
        node = self.store.get_node(node_id)
        if node is None:
            return None
        return _node_to_dict(node)

    def get_neighbors(
        self,
        node_id: str,
        direction: str = "both",
        edge_kinds: list[str] | None = None,
    ) -> list[dict]:
        """Return neighbor nodes of *node_id*."""
        ek: set[EdgeKind] | None = None
        if edge_kinds:
            ek = {EdgeKind(k) for k in edge_kinds}
        neighbor_ids = self.store.neighbors(node_id, edge_kinds=ek, direction=direction)
        result: list[dict] = []
        for nid in sorted(neighbor_ids):
            node = self.store.get_node(nid)
            if node is not None:
                result.append(_node_to_dict(node))
        return result

    def get_ego(
        self,
        center: str,
        radius: int = 2,
        edge_kinds: list[str] | None = None,
    ) -> dict:
        """Return the ego subgraph around *center*."""
        ek: set[EdgeKind] | None = None
        if edge_kinds:
            ek = {EdgeKind(k) for k in edge_kinds}
        ego_store = self.store.ego(center, radius=radius, edge_kinds=ek)
        return _store_to_dict(ego_store)

    def find_cycles(self, limit: int = 100) -> list[list[str]]:
        """Return cycles in the graph (up to *limit*)."""
        return self.store.find_cycles(limit)

    def shortest_path(self, source: str, target: str) -> list[str] | None:
        """Return shortest path between two nodes, or None."""
        try:
            return self.store.shortest_path(source, target)
        except Exception:
            return None

    def consumers_of(self, target_id: str) -> dict:
        """Find nodes that consume from *target_id*."""
        result = GraphQuery(self.store).consumers_of(target_id).execute()
        return _store_to_dict(result)

    def producers_of(self, target_id: str) -> dict:
        """Find nodes that produce to *target_id*."""
        result = GraphQuery(self.store).producers_of(target_id).execute()
        return _store_to_dict(result)

    def callers_of(self, target_id: str) -> dict:
        """Find nodes that call *target_id*."""
        result = GraphQuery(self.store).callers_of(target_id).execute()
        return _store_to_dict(result)

    def dependencies_of(self, module_id: str) -> dict:
        """Find modules that *module_id* depends on."""
        result = GraphQuery(self.store).dependencies_of(module_id).execute()
        return _store_to_dict(result)

    def dependents_of(self, module_id: str) -> dict:
        """Find modules that depend on *module_id*."""
        result = GraphQuery(self.store).dependents_of(module_id).execute()
        return _store_to_dict(result)

    def generate_flow(
        self, view: str = "overview", fmt: str = "json"
    ) -> dict | str:
        """Generate a flow diagram for the given view and format."""
        engine = FlowEngine(self.store)
        diagram = engine.generate(view)
        if fmt == "json":
            return diagram.to_dict()
        return engine.render(diagram, fmt)

    def generate_all_flows(self) -> dict:
        """Generate all flow diagrams as JSON dicts."""
        engine = FlowEngine(self.store)
        return {
            name: diagram.to_dict()
            for name, diagram in engine.generate_all().items()
        }

    def run_analysis(self, incremental: bool = True) -> dict:
        """Run the analysis pipeline and replace the in-memory store."""
        with self._lock:
            analyzer = Analyzer(self._config)
            result: AnalysisResult = analyzer.run(self._path, incremental=incremental)
            self._store = result.graph
        return self.get_stats()

    def query_cypher(
        self, query: str, params: dict | None = None
    ) -> list[dict]:
        """Execute a Cypher query against the graph backend."""
        if not self.store.supports_cypher:
            raise ValueError(
                f"Backend '{self._backend_name}' does not support Cypher queries. "
                "Use kuzu or another Cypher-capable backend."
            )
        return self.store.query_cypher(query, params)

    def find_component_by_file(self, file_path: str) -> dict:
        """Find all graph components defined in a source file."""
        matching_nodes: list[GraphNode] = []
        for node in sorted(self.store.all_nodes(), key=lambda n: n.id):
            if (
                node.location
                and node.location.file_path
                and (
                    node.location.file_path.endswith(file_path)
                    or file_path in node.location.file_path
                )
            ):
                matching_nodes.append(node)

        components: list[dict] = []
        for node in matching_nodes:
            neighbor_ids = self.store.neighbors(node.id)
            neighbors = []
            for nid in sorted(neighbor_ids):
                nb = self.store.get_node(nid)
                if nb is not None:
                    neighbors.append(_node_to_dict(nb))
            components.append({
                "node": _node_to_dict(node),
                "neighbors": neighbors,
            })

        return {"file": file_path, "components": components}

    def trace_impact(self, node_id: str, depth: int = 3) -> dict:
        """BFS impact trace from *node_id* following outgoing edges."""
        propagation_kinds = {
            EdgeKind.DEPENDS_ON,
            EdgeKind.IMPORTS,
            EdgeKind.CALLS,
            EdgeKind.QUERIES,
            EdgeKind.CONNECTS_TO,
        }

        depth = min(depth, 10)
        visited: set[str] = {node_id}
        frontier: set[str] = {node_id}
        impacted_nodes: list[GraphNode] = []
        relevant_edges: list[GraphEdge] = []

        for _ in range(depth):
            next_frontier: set[str] = set()
            for current_id in sorted(frontier):
                for edge in self.store.all_edges():
                    if (
                        edge.source == current_id
                        and edge.kind in propagation_kinds
                        and edge.target not in visited
                    ):
                        visited.add(edge.target)
                        next_frontier.add(edge.target)
                        relevant_edges.append(edge)
                        target_node = self.store.get_node(edge.target)
                        if target_node is not None:
                            impacted_nodes.append(target_node)
            frontier = next_frontier
            if not frontier:
                break

        return {
            "root": node_id,
            "depth": depth,
            "impacted": [
                _node_to_dict(n)
                for n in sorted(impacted_nodes, key=lambda n: n.id)
            ],
            "edges": [
                _edge_to_dict(e)
                for e in sorted(relevant_edges, key=lambda e: (e.source, e.target))
            ],
        }

    def find_related_endpoints(self, identifier: str) -> list[dict]:
        """Find ENDPOINT nodes reachable (up to 3 hops) from matching nodes."""
        identifier_lower = identifier.lower()

        # Find seed nodes matching the identifier
        seed_ids: set[str] = set()
        for node in self.store.all_nodes():
            if (
                identifier_lower in node.id.lower()
                or identifier_lower in node.label.lower()
                or (node.fqn and identifier_lower in node.fqn.lower())
            ):
                seed_ids.add(node.id)

        # BFS up to 3 hops to find ENDPOINT nodes
        visited: set[str] = set(seed_ids)
        frontier: set[str] = set(seed_ids)
        endpoints: dict[str, GraphNode] = {}  # deduplicate by id

        for _ in range(3):
            next_frontier: set[str] = set()
            for nid in sorted(frontier):
                for neighbor_id in self.store.neighbors(nid):
                    if neighbor_id not in visited:
                        visited.add(neighbor_id)
                        next_frontier.add(neighbor_id)
                        nb = self.store.get_node(neighbor_id)
                        if nb is not None and nb.kind == NodeKind.ENDPOINT:
                            endpoints[nb.id] = nb
            frontier = next_frontier
            if not frontier:
                break

        # Also check seed nodes themselves
        for sid in seed_ids:
            node = self.store.get_node(sid)
            if node is not None and node.kind == NodeKind.ENDPOINT:
                endpoints[node.id] = node

        return [
            _node_to_dict(n)
            for n in sorted(endpoints.values(), key=lambda n: n.id)
        ]

    def search_graph(self, query: str, limit: int = 20) -> list[dict]:
        """Case-insensitive substring search across node fields."""
        query_lower = query.lower()
        matches: list[GraphNode] = []

        for node in self.store.all_nodes():
            if (
                query_lower in node.id.lower()
                or query_lower in node.label.lower()
                or (node.fqn and query_lower in node.fqn.lower())
                or (node.module and query_lower in node.module.lower())
                or any(
                    query_lower in str(v).lower()
                    for v in node.properties.values()
                )
            ):
                matches.append(node)

        matches.sort(key=lambda n: n.label)
        return [_node_to_dict(n) for n in matches[:limit]]

    def read_file(self, file_path: str) -> str:
        """Read a file from the codebase, preventing path traversal."""
        resolved = (self._path / file_path).resolve()
        if not str(resolved).startswith(str(self._path)):
            raise ValueError(
                f"Path '{file_path}' resolves outside the codebase root."
            )
        if not resolved.is_file():
            raise ValueError(f"File not found: {file_path}")
        return resolved.read_text(encoding="utf-8", errors="replace")
