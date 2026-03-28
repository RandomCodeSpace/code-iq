"""NetworkX-backed graph backend."""

from __future__ import annotations

import logging
from typing import Any

import networkx as nx

from code_intelligence.models.graph import (
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
)

logger = logging.getLogger(__name__)


class NetworkXBackend:
    """In-memory graph backend using NetworkX MultiDiGraph."""

    def __init__(self) -> None:
        self._g: nx.MultiDiGraph = nx.MultiDiGraph()

    @property
    def node_count(self) -> int:
        return self._g.number_of_nodes()

    @property
    def edge_count(self) -> int:
        return self._g.number_of_edges()

    def add_node(self, node: GraphNode) -> None:
        if node.id in self._g:
            logger.debug("Duplicate node ID %s, keeping first", node.id)
            return
        self._g.add_node(node.id, **node.model_dump())

    def add_edge(self, edge: GraphEdge) -> None:
        # Only add edge if both nodes exist — prevents NetworkX from
        # auto-creating phantom nodes for dangling references.
        if edge.source not in self._g or edge.target not in self._g:
            logger.debug(
                "Skipping edge %s -> %s: missing node(s)",
                edge.source, edge.target,
            )
            return
        self._g.add_edge(edge.source, edge.target, **edge.model_dump())

    def clear(self) -> None:
        self._g.clear()

    def get_node(self, node_id: str) -> GraphNode | None:
        if node_id not in self._g:
            return None
        return GraphNode(**self._g.nodes[node_id])

    def has_node(self, node_id: str) -> bool:
        return node_id in self._g

    def get_edges_between(self, source: str, target: str) -> list[GraphEdge]:
        if not self._g.has_edge(source, target):
            return []
        return [GraphEdge(**data) for _key, data in self._g[source][target].items()]

    def all_nodes(self) -> list[GraphNode]:
        return [
            GraphNode(**data)
            for _, data in self._g.nodes(data=True)
            if "id" in data and "kind" in data
        ]

    def all_edges(self) -> list[GraphEdge]:
        return [
            GraphEdge(**data)
            for _, _, data in self._g.edges(data=True)
            if "source" in data and "target" in data
        ]

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

    def neighbors(self, node_id: str, edge_kinds: set[EdgeKind] | None = None, direction: str = "both") -> list[str]:
        result: set[str] = set()
        if direction in ("out", "both"):
            for _, target, data in self._g.out_edges(node_id, data=True):
                if edge_kinds is None or EdgeKind(data.get("kind", "")) in edge_kinds:
                    result.add(target)
        if direction in ("in", "both"):
            for source, _, data in self._g.in_edges(node_id, data=True):
                if edge_kinds is None or EdgeKind(data.get("kind", "")) in edge_kinds:
                    result.add(source)
        return sorted(result)

    def find_cycles(self, limit: int = 100) -> list[list[str]]:
        cycles: list[list[str]] = []
        for cycle in nx.simple_cycles(self._g):
            cycles.append(cycle)
            if len(cycles) >= limit:
                break
        return cycles

    def shortest_path(self, source: str, target: str) -> list[str] | None:
        try:
            return nx.shortest_path(self._g, source, target)
        except nx.NetworkXNoPath:
            return None

    def subgraph(self, node_ids: set[str]) -> NetworkXBackend:
        new_backend = NetworkXBackend()
        sub = self._g.subgraph(node_ids)
        new_backend._g = nx.MultiDiGraph(sub)
        return new_backend

    def update_node_properties(self, node_id: str, properties: dict[str, Any]) -> None:
        if node_id in self._g:
            data = self._g.nodes[node_id]
            props = data.get("properties", {})
            props.update(properties)
            data["properties"] = props

    def close(self) -> None:
        pass  # In-memory, nothing to close
