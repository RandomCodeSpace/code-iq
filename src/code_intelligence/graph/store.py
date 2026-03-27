"""NetworkX-backed graph store with typed operations."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

import networkx as nx

from code_intelligence.models.graph import (
    CodeGraph,
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
)


class GraphStore:
    """Wraps NetworkX MultiDiGraph with typed node/edge operations."""

    def __init__(self) -> None:
        self._g: nx.MultiDiGraph = nx.MultiDiGraph()

    @property
    def graph(self) -> nx.MultiDiGraph:
        return self._g

    @property
    def node_count(self) -> int:
        return self._g.number_of_nodes()

    @property
    def edge_count(self) -> int:
        return self._g.number_of_edges()

    def add_node(self, node: GraphNode) -> None:
        self._g.add_node(node.id, **node.model_dump())

    def add_edge(self, edge: GraphEdge) -> None:
        # Let NetworkX auto-assign unique integer keys to avoid
        # collisions when multiple edges of the same kind connect the same pair.
        self._g.add_edge(
            edge.source,
            edge.target,
            **edge.model_dump(),
        )

    def get_node(self, node_id: str) -> GraphNode | None:
        if node_id not in self._g:
            return None
        data = self._g.nodes[node_id]
        return GraphNode(**data)

    def get_edges_between(self, source: str, target: str) -> list[GraphEdge]:
        if not self._g.has_edge(source, target):
            return []
        edges = []
        for _key, data in self._g[source][target].items():
            edges.append(GraphEdge(**data))
        return edges

    def all_nodes(self) -> list[GraphNode]:
        result = []
        for _, data in self._g.nodes(data=True):
            if "id" in data and "kind" in data:
                result.append(GraphNode(**data))
        return result

    def all_edges(self) -> list[GraphEdge]:
        result = []
        for _, _, data in self._g.edges(data=True):
            if "source" in data and "target" in data:
                result.append(GraphEdge(**data))
        return result

    def nodes_by_kind(self, kind: NodeKind) -> list[GraphNode]:
        return [
            GraphNode(**data)
            for _, data in self._g.nodes(data=True)
            if data.get("kind") == kind.value and "id" in data
        ]

    def edges_by_kind(self, kind: EdgeKind) -> list[GraphEdge]:
        return [
            GraphEdge(**data)
            for _, _, data in self._g.edges(data=True)
            if data.get("kind") == kind.value and "source" in data
        ]

    def neighbors(
        self,
        node_id: str,
        edge_kinds: set[EdgeKind] | None = None,
        direction: str = "both",
    ) -> list[str]:
        """Get neighbor node IDs, optionally filtered by edge kind and direction."""
        result: set[str] = set()
        if direction in ("out", "both"):
            for _, target, data in self._g.out_edges(node_id, data=True):
                if edge_kinds is None or EdgeKind(data.get("kind", "")) in edge_kinds:
                    result.add(target)
        if direction in ("in", "both"):
            for source, _, data in self._g.in_edges(node_id, data=True):
                if edge_kinds is None or EdgeKind(data.get("kind", "")) in edge_kinds:
                    result.add(source)
        return list(result)

    def subgraph(self, node_ids: set[str]) -> GraphStore:
        """Create a new GraphStore containing only the specified nodes and edges between them."""
        new_store = GraphStore()
        sub = self._g.subgraph(node_ids)
        new_store._g = nx.MultiDiGraph(sub)
        return new_store

    def ego(
        self,
        center: str,
        radius: int = 2,
        edge_kinds: set[EdgeKind] | None = None,
    ) -> GraphStore:
        """Extract an N-hop neighborhood around a center node."""
        if center not in self._g:
            return GraphStore()

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
        """Create a filtered copy of the graph."""
        new_store = GraphStore()

        for _, data in self._g.nodes(data=True):
            if "id" not in data or "kind" not in data:
                continue
            node = GraphNode(**data)
            if node_filter is None or node_filter(node):
                new_store.add_node(node)

        for _, _, data in self._g.edges(data=True):
            if "source" not in data or "target" not in data:
                continue
            edge = GraphEdge(**data)
            if edge.source in new_store._g and edge.target in new_store._g:
                if edge_filter is None or edge_filter(edge):
                    new_store.add_edge(edge)

        return new_store

    def find_cycles(self, limit: int = 100) -> list[list[str]]:
        """Find simple cycles in the graph, up to *limit* results."""
        cycles: list[list[str]] = []
        for cycle in nx.simple_cycles(self._g):
            cycles.append(cycle)
            if len(cycles) >= limit:
                break
        return cycles

    def shortest_path(self, source: str, target: str) -> list[str] | None:
        """Find shortest path between two nodes."""
        try:
            return nx.shortest_path(self._g, source, target)
        except nx.NetworkXNoPath:
            return None

    def to_model(self) -> CodeGraph:
        """Serialize to Pydantic CodeGraph model."""
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
        """Load from Pydantic CodeGraph model."""
        self._g.clear()
        for node in code_graph.nodes:
            self.add_node(node)
        for edge in code_graph.edges:
            self.add_edge(edge)
