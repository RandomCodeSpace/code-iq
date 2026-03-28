"""Graph store facade delegating to a pluggable backend."""

from __future__ import annotations

import logging
import warnings
from collections.abc import Callable
from typing import Any

from osscodeiq.graph.backend import CypherBackend, GraphBackend
from osscodeiq.models.graph import (
    CodeGraph,
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
)

logger = logging.getLogger(__name__)


class GraphStore:
    """Public API for graph operations. Delegates to a pluggable backend."""

    def __init__(self, backend: GraphBackend | None = None) -> None:
        if backend is None:
            from osscodeiq.graph.backends.networkx import NetworkXBackend
            backend = NetworkXBackend()
        self._backend: GraphBackend = backend

    @property
    def graph(self) -> Any:
        """Deprecated. Direct backend access; only works with NetworkXBackend."""
        warnings.warn(
            "GraphStore.graph is deprecated. Use public API methods instead.",
            DeprecationWarning,
            stacklevel=2,
        )
        if hasattr(self._backend, "_g"):
            return self._backend._g
        raise AttributeError("Backend does not expose raw graph object")

    @property
    def node_count(self) -> int:
        return self._backend.node_count

    @property
    def edge_count(self) -> int:
        return self._backend.edge_count

    def add_node(self, node: GraphNode) -> None:
        self._backend.add_node(node)

    def add_edge(self, edge: GraphEdge) -> None:
        self._backend.add_edge(edge)

    def get_node(self, node_id: str) -> GraphNode | None:
        return self._backend.get_node(node_id)

    def get_edges_between(self, source: str, target: str) -> list[GraphEdge]:
        return self._backend.get_edges_between(source, target)

    def all_nodes(self) -> list[GraphNode]:
        return self._backend.all_nodes()

    def all_edges(self) -> list[GraphEdge]:
        return self._backend.all_edges()

    def nodes_by_kind(self, kind: NodeKind) -> list[GraphNode]:
        return self._backend.nodes_by_kind(kind)

    def edges_by_kind(self, kind: EdgeKind) -> list[GraphEdge]:
        return self._backend.edges_by_kind(kind)

    def neighbors(
        self,
        node_id: str,
        edge_kinds: set[EdgeKind] | None = None,
        direction: str = "both",
    ) -> list[str]:
        return self._backend.neighbors(node_id, edge_kinds, direction)

    def subgraph(self, node_ids: set[str]) -> GraphStore:
        return GraphStore(backend=self._backend.subgraph(node_ids))

    def ego(
        self,
        center: str,
        radius: int = 2,
        edge_kinds: set[EdgeKind] | None = None,
    ) -> GraphStore:
        if not self._backend.has_node(center):
            return GraphStore(backend=type(self._backend)() if callable(type(self._backend)) else None)

        radius = min(radius, 10)
        visited: set[str] = {center}
        frontier: set[str] = {center}

        for _ in range(radius):
            next_frontier: set[str] = set()
            for node_id in frontier:
                next_frontier.update(
                    n for n in self.neighbors(node_id, edge_kinds, "both")
                    if n not in visited
                )
            visited.update(next_frontier)
            frontier = next_frontier

        return self.subgraph(visited)

    def filter(
        self,
        node_filter: Callable[[GraphNode], bool] | None = None,
        edge_filter: Callable[[GraphEdge], bool] | None = None,
    ) -> GraphStore:
        new_store = GraphStore()  # Always uses NetworkX for filtered results

        for node in self.all_nodes():
            if node_filter is None or node_filter(node):
                new_store.add_node(node)

        for edge in self.all_edges():
            if new_store._backend.has_node(edge.source) and new_store._backend.has_node(edge.target):
                if edge_filter is None or edge_filter(edge):
                    new_store.add_edge(edge)

        return new_store

    def find_cycles(self, limit: int = 100) -> list[list[str]]:
        return self._backend.find_cycles(limit)

    def shortest_path(self, source: str, target: str) -> list[str] | None:
        return self._backend.shortest_path(source, target)

    def update_node_properties(self, node_id: str, properties: dict[str, Any]) -> None:
        self._backend.update_node_properties(node_id, properties)

    def to_model(self) -> CodeGraph:
        nodes = self.all_nodes()
        edges = self.all_edges()
        node_counts: dict[str, int] = {}
        for n in nodes:
            k = n.kind.value
            node_counts[k] = node_counts.get(k, 0) + 1
        edge_counts: dict[str, int] = {}
        for e in edges:
            k = e.kind.value
            edge_counts[k] = edge_counts.get(k, 0) + 1

        return CodeGraph(
            nodes=nodes,
            edges=edges,
            metadata={
                "stats": {
                    "total_nodes": len(nodes),
                    "total_edges": len(edges),
                    "node_counts_by_kind": node_counts,
                    "edge_counts_by_kind": edge_counts,
                },
            },
        )

    def from_model(self, code_graph: CodeGraph) -> None:
        self._backend.clear()
        for node in code_graph.nodes:
            self._backend.add_node(node)
        for edge in code_graph.edges:
            self._backend.add_edge(edge)

    @property
    def supports_cypher(self) -> bool:
        return isinstance(self._backend, CypherBackend)

    def query_cypher(self, cypher: str, params: dict[str, Any] | None = None) -> list[dict[str, Any]]:
        if not isinstance(self._backend, CypherBackend):
            raise NotImplementedError(
                f"Backend {type(self._backend).__name__} does not support Cypher. "
                "Use kuzu, neo4j, or age backend."
            )
        return self._backend.query_cypher(cypher, params)

    def close(self) -> None:
        self._backend.close()
